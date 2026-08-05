(ns llm-context.public-semantic-evaluation
  "Run the pinned public cross-repository semantic evaluation.

  This runner intentionally treats source checkouts as external inputs. It
  never clones, copies, or archives them, and all query-level output is written
  below each checkout's ignored .llm-context directory."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.retrieval-corpus :as corpus]
            [llm-context.semantic.mode :as retrieval-mode]
            [llm-context.version :as version])
  (:import [java.lang ProcessHandle]
           [java.nio.file Files LinkOption Path Paths]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util Random]
           [java.util.concurrent TimeUnit]))

(def default-manifest "bench/public-semantic-evaluation/manifest.edn")
(def default-repetitions 3)
(def modes [:fts-only :lateon-only :hybrid])
(def splits [:development :held-out])
(def default-stage-timeouts-ms
  {:doctor (* 5 60 1000)
   :analysis (* 30 60 1000)
   :service-start (* 5 60 1000)
   :semantic-sync (* 30 60 1000)
   :status (* 5 60 1000)
   :benchmark (* 15 60 1000)
   :service-stop (* 5 60 1000)})
(def ^:dynamic *heartbeat-ms* 30000)
(def ^:private retrieval-metric-keys
  [:search-recall-at-10?
   :search-recall-at-20?
   :search-recall-at-50?
   :search-hit?
   :reciprocal-rank
   :ndcg
   :hard-negative-before-relevant?
   :lateon?
   :seed-lateon?])
(def ^:private context-metric-keys [:seed-hit? :packet-hit? :context-error?])
(def ^:private metric-keys (into retrieval-metric-keys context-metric-keys))

(defn- metric-keys-for [mode]
  (if (= :hybrid mode)
    metric-keys
    retrieval-metric-keys))

(defn- fail! [message data]
  (throw (ex-info message (merge {:exit-code 1} data))))

(defn read-manifest [path]
  (let [manifest-path (.toRealPath
                       (Paths/get (str path) (make-array String 0))
                       (make-array LinkOption 0))]
    (with-open [reader (java.io.PushbackReader. (io/reader (str manifest-path)))]
      (with-meta (edn/read {:eof nil} reader)
        {::manifest-directory (.getParent manifest-path)}))))

(defn validate-manifest!
  "Validate the public suite contract without touching any checkout."
  [manifest]
  (let [repositories (:repositories manifest)
        ids (set (map :id repositories))
        expected (reduce + 0 (map #(reduce + 0 (vals (:expected-queries %)))
                                  repositories))
        errors
        (cond-> []
          (not= 1 (:suite/version manifest))
          (conj ":suite/version must be 1")

          (not= 2 (:corpus/version manifest))
          (conj ":corpus/version must be 2")

          (not= #{:clojure-lsp :re-frame :metabase} ids)
          (conj ":repositories must contain clojure-lsp, re-frame, and metabase")

          (not= 3 (count repositories))
          (conj ":repositories must contain exactly three entries")

          (not= 120 expected)
          (conj ":expected-queries must total 120")

          (not (every? (fn [{:keys [commit checkout url expected-queries corpus]}]
                         (and (string? checkout)
                              (re-matches #"[0-9a-f]{40}" (or commit ""))
                              (string? url)
                              (every? #(pos-int? (get expected-queries %)) splits)
                              (every? #(string? (get corpus %)) splits)))
                       repositories))
          (conj "every repository must have a URL, 40-character commit, both splits, and positive counts"))]
    (when (seq errors)
      (fail! (str "Invalid public evaluation manifest: " (str/join "; " errors))
             {:errors errors}))
    manifest))

(defn- root-path ^Path [root]
  (.toRealPath (Paths/get (str root) (make-array String 0))
               (make-array LinkOption 0)))

(defn checkout-path ^Path [root checkout]
  (let [root (root-path root)
        path (.normalize (.resolve root checkout))]
    (when-not (.startsWith path root)
      (fail! "Checkout path escapes the evaluation root"
             {:checkout checkout}))
    path))

(defn- sha256 [path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (apply str
           (map #(format "%02x" (bit-and % 0xff))
                (.digest digest (Files/readAllBytes ^Path path))))))

(defn- sha256-text [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (apply str
           (map #(format "%02x" (bit-and % 0xff))
                (.digest digest (.getBytes (str value)
                                          java.nio.charset.StandardCharsets/UTF_8))))))

(defn- write-log! [checkout relative content]
  (let [path (.normalize (.resolve ^Path checkout relative))]
    (when-not (.startsWith path (.normalize (.resolve ^Path checkout ".llm-context")))
      (fail! "Evaluation log must remain below .llm-context" {:path relative}))
    (when-let [parent (.getParent path)]
      (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0)))
    (spit (str path) content)
    path))

(defn- terminate-tree! [^Process process]
  (let [handle (.toHandle process)
        descendants (with-open [stream (.descendants handle)]
                      (vec (iterator-seq (.iterator stream))))]
    (doseq [^ProcessHandle descendant (reverse descendants)]
      (.destroy descendant))
    (.destroy process)
    (when-not (.waitFor process 5 TimeUnit/SECONDS)
      (doseq [^ProcessHandle descendant (reverse descendants)]
        (when (.isAlive descendant)
          (.destroyForcibly descendant)))
      (.destroyForcibly process)
      (.waitFor process 5 TimeUnit/SECONDS))))

(defn- latest-progress [^Path stdout ^Path stderr]
  (try
    (->> [stdout stderr]
         (mapcat (fn [path]
                   (when (Files/exists path (make-array LinkOption 0))
                     (Files/readAllLines path))))
         (filter #(or (str/includes? % "analyzer")
                      (str/includes? % "Analyz")
                      (str/includes? % "persist")))
         last)
    (catch Throwable _ nil)))

(defn run-process!
  "Run one bounded stage with output redirected before process start."
  [checkout log-name stage command timeout-ms]
  (let [stdout (write-log! checkout log-name "")
        stderr (write-log! checkout (str log-name ".stderr.log") "")
        result-log (str log-name ".result.edn")
        builder (doto (ProcessBuilder. ^java.util.List (vec command))
                  (.directory (.toFile (root-path ".")))
                  (.redirectOutput (.toFile stdout))
                  (.redirectError (.toFile stderr)))
        started (System/currentTimeMillis)
        process (.start builder)
        running (atom true)
        heartbeat
        (future
          (while @running
            (Thread/sleep (long *heartbeat-ms*))
            (when (and @running (.isAlive process))
              (let [latest (latest-progress stdout stderr)]
                (println
                 (pr-str
                  (cond-> {:event :public-stage-heartbeat
                           :stage stage
                           :elapsed-ms (- (System/currentTimeMillis) started)}
                    latest (assoc :latest-progress latest))))
                (flush)))))
        completed? (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
        _ (when-not completed? (terminate-tree! process))
        _ (reset! running false)
        _ (future-cancel heartbeat)
        exit (if completed? (.exitValue process) :timeout)
        result {:stage stage :command (vec command) :exit exit
                :timed-out? (not completed?)
                :elapsed-ms (- (System/currentTimeMillis) started)
                :stdout-log (str stdout) :stderr-log (str stderr)}]
    (write-log! checkout result-log (str (pr-str result) "\n"))
    (when-not (and completed? (zero? exit))
      (fail! (if completed?
               "Public evaluation stage failed; inspect the external checkout logs"
               "Public evaluation stage timed out; inspect the external checkout logs")
             result))
    (assoc result :out (Files/readString stdout) :err "")))

(defn- llm-command [checkout args quiet?]
  (vec (concat ["clojure" "-M" "-m" "llm-context.main"]
               (when quiet? ["-q"])
               ["-C" (str checkout)] args)))

(defn- run-and-log! [checkout log-name stage args timeouts]
  (run-process! checkout log-name stage
                (llm-command checkout args (not= :analysis stage))
                (get timeouts stage)))

(defn- run-benchmark-and-log! [checkout log-name args timeouts]
  (run-process! checkout log-name :benchmark
                (into ["clojure" "-M:semantic-bench"] args)
                (:benchmark timeouts)))

(defn- git-command [checkout args]
  (let [temporary (Files/createTempFile
                   "llm-context-public-git-" ".log"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (let [process (-> (ProcessBuilder.
                         ^java.util.List
                         (vec (concat ["git" "-C" (str checkout)] args)))
                        (.redirectErrorStream true)
                        (.redirectOutput (.toFile temporary))
                        .start)
            completed? (.waitFor process 60 TimeUnit/SECONDS)]
        (when-not completed? (terminate-tree! process))
        {:exit (if completed? (.exitValue process) 124)
         :out (Files/readString temporary) :err ""})
      (finally
        (Files/deleteIfExists temporary)))))

(defn- clean-and-pinned! [checkout {:keys [commit]}]
  (when-not (Files/isDirectory checkout (make-array LinkOption 0))
    (fail! "Pinned public checkout is missing" {:checkout (str checkout)}))
  (let [status (git-command checkout ["status" "--porcelain=v1"
                                      "--untracked-files=all"])
        head (git-command checkout ["rev-parse" "HEAD"])
        ignored (git-command checkout ["check-ignore" "--no-index" "-q"
                                       ".llm-context"])]
    (when-not (zero? (:exit status))
      (fail! "Unable to inspect pinned checkout status" {:checkout (str checkout)}))
    (when (seq (str/trim (:out status)))
      (fail! "Public checkout is dirty" {:checkout (str checkout)}))
    (when-not (and (zero? (:exit head))
                   (= commit (str/trim (:out head))))
      (fail! "Public checkout is not at its manifest-pinned commit"
             {:checkout (str checkout)}))
    (when-not (zero? (:exit ignored))
      (fail! "Public checkout does not ignore .llm-context generated state"
             {:checkout (str checkout)}))
    {:commit commit}))

(defn- parse-edn-output [result]
  (try
    (edn/read-string (:out result))
    (catch Throwable _
      (fail! "Public evaluation command returned non-EDN status output" {}))))

(defn- loopback-endpoint? [endpoint]
  (when (string? endpoint)
    (try
      (let [uri (java.net.URI/create endpoint)
            host (.getHost uri)]
        (and (= "http" (.getScheme uri))
             (or (= "localhost" host)
                 (= "::1" host)
                 (= "[::1]" host)
                 (and host (str/starts-with? host "127.")))))
      (catch Throwable _ false))))

(defn synchronized-status? [status]
  (and (= :complete (:completeness status))
       (zero? (:pending status 0))
       (zero? (:leased status 0))
       (zero? (:failed status 0))
       (zero? (:dirty status 0))
       (loopback-endpoint? (get-in status [:runtime :endpoint]))))

(defn- start-and-synchronize! [checkout timeouts]
  (run-and-log! checkout ".llm-context/public-semantic-evaluation/preflight/doctor.edn"
                :doctor ["doctor"] timeouts)
  (run-and-log! checkout ".llm-context/public-semantic-evaluation/preflight/analyze.edn"
                :analysis ["analyze" "--full"] timeouts)
  (run-and-log! checkout ".llm-context/public-semantic-evaluation/preflight/service-start.edn"
                :service-start ["service" "start"] timeouts)
  (let [stage-timeout (:semantic-sync timeouts)
        cli-timeout (max 1 (- stage-timeout 30000))]
    (run-and-log! checkout ".llm-context/public-semantic-evaluation/preflight/sync.edn"
                  :semantic-sync
                  ["semantic" "sync" "--wait" "--timeout-ms"
                   (str cli-timeout)]
                  timeouts))
  (let [status-result
        (run-and-log! checkout
                      ".llm-context/public-semantic-evaluation/preflight/status.edn"
                      :status ["semantic" "status"] timeouts)
        status (parse-edn-output status-result)]
    (when-not (synchronized-status? status)
      (fail! "Public checkout did not reach complete semantic coverage" {}))
    status))

(defn- stop-service! [checkout timeouts]
  (try
    (run-and-log!
     checkout ".llm-context/public-semantic-evaluation/preflight/service-stop.edn"
     :service-stop ["service" "stop"] timeouts)
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- corpus-path ^Path [manifest-directory repository split]
  (.normalize
   (.resolve ^Path manifest-directory (get-in repository [:corpus split]))))

(defn- validate-corpora! [checkout manifest-directory repository]
  (into {}
        (for [split splits
              :let [relative (get-in repository [:corpus split])
                    path (corpus-path manifest-directory repository split)
                    validation (corpus/validate! (str checkout) (str path))
                    expected (get-in repository [:expected-queries split])]]
          (do
            (when-not (= expected (:queries validation))
              (fail! "Public corpus query count does not match the manifest"
                     {:repository (:id repository) :split split}))
            [split {:path relative
                    :hash (sha256 path)
                    :validation (select-keys validation
                                             [:corpus/version :queries :languages
                                              :query-types])}]))))

(defn- deterministic-view [result]
  {:retrieval-mode (:retrieval-mode result)
   :queries
   (mapv #(select-keys % (concat [:id :language :query-type :domain]
                                 metric-keys))
         (:query-results result))})

(defn- benchmark-split!
  [checkout manifest-directory repository split repetitions timeouts]
  (let [corpus-path (str (corpus-path manifest-directory repository split))]
    (into {}
          (for [mode modes]
            (let [runs
                  (mapv
                   (fn [run]
                     (let [output-relative
                           (format ".llm-context/public-semantic-evaluation/%s/%s/run-%d.edn"
                                   (name split) (name mode) run)
                           output-path (.resolve ^Path checkout output-relative)
                           result
                           (run-benchmark-and-log!
                            checkout
                            (format ".llm-context/public-semantic-evaluation/%s/%s/run-%d.log"
                                    (name split) (name mode) run)
                            [(str checkout) corpus-path
                             "--mode" (retrieval-mode/name-of mode)
                             "--output"
                             (str output-path)]
                            timeouts)]
                       ;; Standard output is the privacy-safe summary. Read
                       ;; the complete query-level result only from ignored
                       ;; external state for determinism and aggregation.
                       (parse-edn-output result)
                       (edn/read-string (slurp (str output-path)))))
                   (range 1 (inc repetitions)))]
              (when-not (apply = (map deterministic-view runs))
                (fail! "Repeated public evaluation rankings are not deterministic"
                       {:repository (:id repository) :split split :mode mode}))
              [mode (first runs)])))))

(defn- mean [values]
  (if (seq values)
    (/ (reduce + 0.0 values) (count values))
    0.0))

(defn- metric-value [row metric]
  (let [value (get row metric)]
    (cond
      (boolean? value) (if value 1.0 0.0)
      (number? value) (double value)
      :else nil)))

(defn bootstrap-ci
  "Deterministic percentile bootstrap interval for a vector of scalar values."
  ([values] (bootstrap-ci values 20260805))
  ([values seed]
   (let [values (vec (keep identity values))]
     (when (seq values)
       (let [random (Random. (long seed))
             size (count values)
             samples
             (vec
              (repeatedly 2000
                          (fn []
                            (mean (repeatedly size
                                              (fn []
                                                (nth values (.nextInt random size))))))))
             sorted (vec (sort samples))
             at #(nth sorted (long (* % (dec (count sorted)))))]
         {:low (at 0.025) :high (at 0.975)})))))

(defn metric-summary [rows metric]
  (let [values (vec (keep #(metric-value % metric) rows))]
    {:queries (count values)
     :mean (mean values)
     :bootstrap-95 (bootstrap-ci values)}))

(defn- percentile [values fraction]
  (when (seq values)
    (let [sorted (vec (sort values))
          index (long (Math/floor (* fraction (dec (count sorted)))))]
      (nth sorted index))))

(defn- latency-summary [rows field]
  (let [values (vec (keep field rows))]
    (when (seq values)
      {:queries (count values)
       :mean (mean values)
       :p50 (percentile values 0.50)
       :p95 (percentile values 0.95)
       :max (apply max values)})))

(defn- rows-for [results]
  (vec (mapcat #(get-in % [:result :query-results]) results)))

(defn- slice-summary [rows field metrics]
  (into (sorted-map)
        (map (fn [[value members]]
               [value {:queries (count members)
                       :metrics
                       (into (sorted-map)
                             (map (fn [metric]
                                    [metric (metric-summary members metric)])
                                  metrics))}])
             (sort-by key (group-by field rows)))))

(defn aggregate-mode [results mode]
  (let [results (filter #(= mode (:mode %)) results)
        metrics (metric-keys-for mode)
        rows (rows-for results)
        repository-rows
        (->> results
             (group-by :repository)
             (mapv (fn [[repository members]]
                     {:repository repository
                      :rows (vec (mapcat #(get-in % [:result :query-results])
                                         members))})))
        split-rows
        (->> results
             (group-by :split)
             (mapv (fn [[split members]]
                     {:split split
                      :rows (rows-for members)})))
        macro-values
        (fn [metric]
          (map (fn [repository-row]
                 (mean (keep (fn [row] (metric-value row metric))
                             (:rows repository-row))))
               repository-rows))]
    {:retrieval-mode mode
     :repositories (count repository-rows)
     :queries (count rows)
     :macro (into (sorted-map)
                  (for [metric metrics]
                    [metric {:mean (mean (macro-values metric))
                             :bootstrap-95
                             (bootstrap-ci (vec (macro-values metric)))}]))
     :query-weighted (into (sorted-map)
                           (for [metric metrics]
                             [metric (metric-summary rows metric)]))
     :by-repository
     (into (sorted-map)
           (for [{:keys [repository rows]} repository-rows]
             [repository
              {:queries (count rows)
               :metrics
               (into (sorted-map)
                     (for [metric metrics]
                       [metric (metric-summary rows metric)]))
               :latency-ms {:search (latency-summary rows :search-ms)
                            :context (when (= :hybrid mode)
                                       (latency-summary rows :context-ms))}}]))
     :by-split
     (into (sorted-map)
           (for [{:keys [split rows]} split-rows]
             [split
              {:queries (count rows)
               :metrics
               (into (sorted-map)
                     (for [metric metrics]
                       [metric (metric-summary rows metric)]))
               :latency-ms {:search (latency-summary rows :search-ms)
                            :context (when (= :hybrid mode)
                                       (latency-summary rows :context-ms))}}]))
     :latency-ms {:search (latency-summary rows :search-ms)
                  :context (when (= :hybrid mode)
                             (latency-summary rows :context-ms))}
     :slices {:language (slice-summary rows :language metrics)
              :query-type (slice-summary rows :query-type metrics)
              :domain (slice-summary rows :domain metrics)}}))

(defn- metadata [source-root manifest corpus-results repetitions]
  (let [git (git-command (root-path source-root) ["rev-parse" "HEAD"])
        benchmark-config
        (some (fn [{:keys [runs]}]
                (some (comp :benchmark/config :result) runs))
              corpus-results)]
    {:suite/version (:suite/version manifest)
     :scorer-commit (str/trim (:out git))
     :retrieval-runtime version/value
     :retrieval-modes modes
     :repetitions repetitions
     :benchmark-config benchmark-config
     :timestamp (str (Instant/now))
     :repositories
     (mapv (fn [{:keys [repository corpus]}]
             {:id (:id repository)
              :commit (:commit repository)
              :corpus corpus})
           corpus-results)}))

(def ^:private resume-relative
  ".llm-context/public-semantic-evaluation/resume.edn")

(defn- repository-resume-key
  [checkout repository corpus-results repetitions]
  (let [settings (config/load-config (project/context (str checkout)))
        lateon (get-in settings [:semantic :lateon-code])
        scorer (git-command (root-path ".") ["rev-parse" "HEAD"])]
    {:repository (:id repository)
     :commit (:commit repository)
     :corpus-hashes
     (into (sorted-map)
           (map (fn [[split value]] [split (:hash value)]))
           corpus-results)
     :retrieval-runtime version/value
     :scorer-commit (str/trim (:out scorer))
     :model-revision (:model-revision lateon)
     :configuration-hash
     (sha256-text (pr-str {:lateon lateon :modes modes
                           :repetitions repetitions}))}))

(defn- resumed-result [checkout key]
  (try
    (let [path (.resolve ^Path checkout resume-relative)
          record (edn/read-string (Files/readString path))]
      (when (= key (:key record)) (:result record)))
    (catch Throwable _ nil)))

(defn- write-resume! [checkout key result]
  (write-log! checkout resume-relative
              (str (pr-str {:key key :completed-at (str (Instant/now))
                            :result result})
                   "\n")))

(defn run-suite!
  "Run every manifest repository and return aggregate-only public metadata."
  ([root manifest]
   (run-suite! root manifest {}))
  ([root manifest {:keys [repetitions timeouts resume?]
                   :or {repetitions (or (:repetitions manifest)
                                        default-repetitions)
                        resume? true}}]
   (validate-manifest! manifest)
   (let [source-root (root-path ".")
         manifest-directory (or (::manifest-directory (meta manifest))
                                source-root)
         timeouts (merge default-stage-timeouts-ms timeouts)
         repository-results
         (mapv
          (fn [repository]
            (let [checkout (checkout-path root (:checkout repository))]
              (clean-and-pinned! checkout repository)
              (let [corpus-results (validate-corpora!
                                    checkout manifest-directory repository)
                    resume-key (repository-resume-key
                                checkout repository corpus-results repetitions)]
                (or
                 (when resume? (resumed-result checkout resume-key))
                 (try
                   (let [status (start-and-synchronize! checkout timeouts)
                         runs
                         (vec
                          (for [split splits
                                [mode result]
                                (benchmark-split!
                                 checkout manifest-directory repository split
                                 repetitions timeouts)]
                            {:repository (:id repository)
                             :split split
                             :mode mode
                             :result result}))
                         result
                         {:repository repository
                          :status (select-keys status [:completeness :indexed])
                          :corpus corpus-results
                          :runs runs}]
                     (write-resume! checkout resume-key result)
                     result)
                   (finally
                     (stop-service! checkout timeouts)))))))
          (:repositories manifest))
         run-records
         (vec
          (for [{:keys [repository runs]} repository-results
                {:keys [split mode result]} runs]
            {:repository (:id repository)
             :split split
             :mode mode
             :result result}))]
     {:metadata (metadata source-root manifest repository-results repetitions)
      :aggregates (into (sorted-map)
                        (for [mode modes]
                          [mode (aggregate-mode run-records mode)]))})))

(defn- parse-args [args]
  (loop [remaining args
         result {:root nil :manifest default-manifest :output nil
                 :resume? true :timeouts {}}]
    (if-let [argument (first remaining)]
      (case argument
        "--manifest"
        (if-let [path (second remaining)]
          (recur (nnext remaining) (assoc result :manifest path))
          (fail! "--manifest requires a path" {}))
        "--output"
        (if-let [path (second remaining)]
          (recur (nnext remaining) (assoc result :output path))
          (fail! "--output requires a path" {}))
        "--no-resume"
        (recur (next remaining) (assoc result :resume? false))
        "--stage-timeout"
        (if-let [value (second remaining)]
          (let [[stage seconds] (str/split value #"=" 2)
                stage (keyword stage)
                seconds (parse-long (or seconds ""))]
            (when-not (and (contains? default-stage-timeouts-ms stage)
                           (pos-int? seconds))
              (fail! "--stage-timeout requires STAGE=positive-seconds"
                     {:value value}))
            (recur (nnext remaining)
                   (assoc-in result [:timeouts stage] (* seconds 1000))))
          (fail! "--stage-timeout requires STAGE=positive-seconds" {}))
        (if (:root result)
          (fail! "Only one external checkout root may be supplied" {})
          (recur (next remaining) (assoc result :root argument))))
      (if (:root result)
        result
        (fail! "Usage: clojure -M:public-semantic-evaluation CHECKOUT_ROOT [--manifest PATH] [--output PATH] [--no-resume] [--stage-timeout STAGE=SECONDS]"
               {})))))

(defn -main [& args]
  (try
    (let [{:keys [root manifest output resume? timeouts]} (parse-args args)
          suite (run-suite! root (validate-manifest! (read-manifest manifest))
                            {:resume? resume? :timeouts timeouts})
          rendered (str (pr-str suite) "\n")]
      (if output
        (do
          (spit output rendered)
          (println (pr-str (select-keys suite [:metadata :aggregates]))))
        (println rendered)))
    (catch clojure.lang.ExceptionInfo error
      (binding [*out* *err*]
        (println "Public semantic evaluation failed; inspect the external checkout logs."))
      (System/exit (or (:exit-code (ex-data error)) 1)))
    (finally
      (shutdown-agents))))
