(ns llm-context.semantic-ingestion-qualification
  "Disposable request/concurrency qualification for production-rendered
  semantic documents. This command never starts against the live index."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [datalevin.core :as d]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.semantic.document :as document]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.runtime :as runtime]
            [llm-context.service.lifecycle :as lifecycle]
            [llm-context.source-role :as source-role]
            [llm-context.storage :as storage]
            [llm-context.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files LinkOption OpenOption Path]
           [java.util UUID]
           [java.util.concurrent Callable ExecutionException Executors
            TimeUnit]))

(defn qualification-matrix [request-batches request-concurrencies]
  (vec (for [request-batch request-batches
             request-concurrency request-concurrencies]
         {:request-provider-document-limit request-batch
          :request-concurrency-limit request-concurrency})))

(def matrix
  (qualification-matrix [32 128 300 512] [1 2]))

(def ^:private marker-name ".llm-context-semantic-qualification.edn")
(def ^:private marker-format 1)
(def ^:private visible-attributes
  [:id :symbol-id :file-id :document-hash :model-revision
   :document-version :chunk-index :chunk-count])

(defn- absolute ^Path [^Path root value]
  (let [candidate (if (instance? Path value)
                    value
                    (java.nio.file.Paths/get (str value)
                                              (make-array String 0)))]
    (.normalize (.toAbsolutePath
                 (if (.isAbsolute ^Path candidate)
                   candidate
                   (.resolve root ^Path candidate))))))

(defn- canonical ^Path [^Path path]
  (if (Files/exists path (make-array LinkOption 0))
    (.toRealPath path (make-array LinkOption 0))
    (loop [ancestor (.getParent path)
           suffix [(.getFileName path)]]
      (if (nil? ancestor)
        path
        (if (Files/exists ancestor (make-array LinkOption 0))
          (reduce #(.resolve ^Path %1 ^Path %2)
                  (.toRealPath ancestor (make-array LinkOption 0))
                  (reverse suffix))
          (recur (.getParent ancestor)
                 (conj suffix (.getFileName ancestor))))))))

(defn assert-isolated-destination!
  "Reject a qualification destination equal to, inside, or containing the
  active provider index path. Returns the normalized destination."
  [project settings destination]
  (let [root ^Path (:root project)
        active (canonical (absolute root (:index-path settings)))
        destination (canonical (absolute root destination))]
    (when (or (= active destination)
              (.startsWith destination active)
              (.startsWith active destination))
      (throw
       (ex-info "Qualification destination overlaps the active semantic index"
                {:type :semantic-qualification/unsafe-destination
                 :active-index (str active)
                 :destination (str destination)})))
    destination))

(defn- utf8-size [value]
  (alength (.getBytes ^String value StandardCharsets/UTF_8)))

(defn- round-robin-strata [limit key-fn values]
  (let [groups (->> values
                    (group-by key-fn)
                    (sort-by key)
                    (mapv (comp seq val)))]
    (loop [remaining groups
           selected []]
      (if (or (>= (count selected) limit) (empty? remaining))
        (vec (take limit selected))
        (let [heads (keep first remaining)
              tails (vec (keep next remaining))]
          (recur tails (into selected heads)))))))

(defn- candidate-symbols [graph settings sample-size]
  (let [overrides (get-in settings [:context :source-role-overrides])
        rows
        (d/q '[:find ?symbol-id ?file-id ?path ?language
               :where
               [?symbol :symbol/id ?symbol-id]
               [?symbol :symbol/indexable? true]
               [?symbol :symbol/file ?file]
               [?file :file/id ?file-id]
               [?file :file/path ?path]
               [?file :file/language ?language]]
             (store/database graph))]
    (->> rows
         (map (fn [[symbol-id file-id path language]]
                {:symbol-id symbol-id
                 :file-id file-id
                 :language language
                 :source-role (source-role/classify path overrides)}))
         (sort-by (juxt :symbol-id :file-id))
         (round-robin-strata (* sample-size 8)
                             (juxt :language :source-role)))))

(defn- byte-bucket [bytes]
  (cond
    (< bytes 2048) :small
    (< bytes 8192) :medium
    :else :large))

(defn- rendered-sample [graph project settings sample-size]
  (let [lateon (get-in settings [:semantic :lateon-code])
        candidates (candidate-symbols graph settings sample-size)
        candidate-by-symbol (into {} (map (juxt :symbol-id identity))
                                  candidates)
        rendered
        (mapcat
         (fn [[file-id members]]
           (let [result (document/build-symbols
                         graph project lateon file-id
                         (mapv :symbol-id members))]
             (when-not (= :ready (:status result))
               (throw
                (ex-info "Project source changed during qualification sampling"
                         {:type :semantic-qualification/source-changed
                          :file-id file-id
                          :status (:status result)})))
             (:documents result)))
         (sort-by key (group-by :file-id candidates)))
        annotated
        (mapv
         (fn [value]
           (let [{:keys [language source-role]}
                 (get candidate-by-symbol (:symbol-id value))
                 bytes (reduce + 0 (map (comp utf8-size :text)
                                        (:chunks value)))]
             (assoc value
                    :sample/language language
                    :sample/source-role source-role
                    :sample/text-bytes bytes
                    :sample/byte-bucket (byte-bucket bytes)
                    :sample/chunk-count (count (:chunks value)))))
         rendered)]
    (round-robin-strata
     sample-size
     (juxt :sample/language :sample/source-role :sample/byte-bucket
           :sample/chunk-count)
     (sort-by :symbol-id annotated))))

(defn- exact-visible? [visible expected]
  (let [expected (mapv #(select-keys % visible-attributes) expected)
        actual (mapv #(select-keys % visible-attributes) visible)]
    (and (= (count expected) (count actual))
         (= (set expected) (set actual)))))

(defn- visible-documents [client documents]
  (vec
   (mapcat #(index/indexed-documents client %)
           (partition-all 128 (distinct (map :symbol-id documents))))))

(defn- retriable-visibility-error? [error]
  (let [status (:status (ex-data error))]
    (or (= 429 status)
        (and (int? status) (<= 500 status 599)))))

(defn- await-visible! [client documents timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [result
            (try
              {:visible (visible-documents client documents)}
              (catch clojure.lang.ExceptionInfo error
                (if (retriable-visibility-error? error)
                  {:error error}
                  (throw error))))
            visible (:visible result)
            error (:error result)]
        (cond
          (and error (>= (System/currentTimeMillis) deadline))
          (throw error)

          error (do (Thread/sleep 250) (recur))

          (exact-visible? visible documents) visible
          (>= (System/currentTimeMillis) deadline)
          (throw
           (ex-info "Qualification index did not reach exact visibility"
                    {:type :semantic-qualification/visibility-timeout
                     :expected (count documents)
                     :visible (count visible)}))
          :else (do (Thread/sleep 250) (recur)))))))

(defn- submit-wave! [client wave]
  (let [executor (Executors/newFixedThreadPool (count wave))]
    (try
      (let [started (System/nanoTime)
            futures
            (mapv (fn [batch]
                    (.submit executor
                             ^Callable
                             #(let [request-start (System/nanoTime)]
                                (index/add-documents! client batch)
                                (/ (- (System/nanoTime) request-start)
                                   1000000.0))))
                  wave)
            latencies
            (mapv (fn [future]
                    (try (.get future)
                         (catch ExecutionException error
                           (throw (.getCause error)))))
                  futures)]
        {:elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
         :request-latencies-ms latencies})
      (finally
        (.shutdown executor)
        (.awaitTermination executor 5 TimeUnit/SECONDS)))))

(defn- submit-documents!
  [client documents request-limit concurrency visibility-timeout-ms]
  (let [batches (mapv vec (partition-all request-limit documents))]
    (reduce
     (fn [result wave]
       (let [measured (submit-wave! client wave)
             submitted (into (:submitted-documents result)
                             (mapcat identity wave))
             visibility-start (System/nanoTime)
             visible (await-visible! client submitted visibility-timeout-ms)
             visibility-ms
             (/ (- (System/nanoTime) visibility-start) 1000000.0)]
         (-> result
             (update :submit-ms + (:elapsed-ms measured))
             (update :visibility-ms + visibility-ms)
             (update :request-latencies-ms into
                     (:request-latencies-ms measured))
             (assoc :submitted-documents submitted
                    :visible-documents visible))))
     {:request-count (count batches)
      :submit-ms 0.0
      :visibility-ms 0.0
      :submitted-documents []
      :visible-documents []
      :request-latencies-ms []}
     (partition-all concurrency batches))))

(defn- tree-bytes [^Path root]
  (if-not (Files/exists root (make-array LinkOption 0))
    0
    (with-open [paths (Files/walk root (make-array FileVisitOption 0))]
      (reduce (fn [total ^Path path]
                (if (Files/isRegularFile path (make-array LinkOption 0))
                  (+ total (Files/size path))
                  total))
              0 (iterator-seq (.iterator paths))))))

(defn- process-io [^Process process]
  (let [path (java.nio.file.Paths/get
              (str "/proc/" (.pid process) "/io")
              (make-array String 0))]
    (when (Files/isRegularFile path (make-array LinkOption 0))
      (let [rows (->> (Files/readAllLines path)
                      (keep #(when-let [[_ key value]
                               (re-matches #"([^:]+):\s+(\d+)" %)]
                               [(keyword key) (parse-long value)]))
                      (into {}))]
        {:read-bytes (:read_bytes rows)
         :write-bytes (:write_bytes rows)}))))

(defn- process-rss-bytes [^Process process]
  (let [path (java.nio.file.Paths/get
              (str "/proc/" (.pid process) "/status")
              (make-array String 0))]
    (when (Files/isRegularFile path (make-array LinkOption 0))
      (some (fn [line]
              (when-let [[_ value]
                         (re-matches #"VmRSS:\s+(\d+)\s+kB" line)]
                (* 1024 (parse-long value))))
            (Files/readAllLines path)))))

(defn- percentile [values fraction]
  (when (seq values)
    (let [ordered (vec (sort values))
          index (max 0 (dec (long (Math/ceil (* fraction
                                               (count ordered))))))]
      (nth ordered (min index (dec (count ordered)))))))

(defn- marker-path ^Path [^Path destination]
  (.resolve destination marker-name))

(defn- write-marker! [^Path destination]
  (let [marker {:artifact/type :semantic-ingestion-qualification
                :artifact/format marker-format
                :artifact/path (str destination)
                :artifact/id (str (UUID/randomUUID))
                :artifact/created-at (System/currentTimeMillis)}]
    (Files/writeString (marker-path destination) (pr-str marker)
                       (make-array OpenOption 0))
    marker))

(defn- valid-marker? [^Path destination]
  (try
    (let [value (edn/read-string (Files/readString (marker-path destination)))]
      (and (= :semantic-ingestion-qualification (:artifact/type value))
           (= marker-format (:artifact/format value))
           (= (str destination) (:artifact/path value))))
    (catch Throwable _ false)))

(defn- delete-tree! [^Path destination]
  (when-not (valid-marker? destination)
    (throw
     (ex-info "Refusing to remove an unmarked qualification directory"
              {:type :semantic-qualification/marker-missing
               :destination (str destination)})))
  (with-open [paths (Files/walk destination (make-array FileVisitOption 0))]
    (doseq [^Path path (sort-by #(.getNameCount ^Path %) >
                                (iterator-seq (.iterator paths)))]
      (Files/deleteIfExists path))))

(defn- run-case! [project settings documents matrix-case]
  (let [active (absolute (:root project) (:index-path settings))
        parent (.getParent active)
        _ (Files/createDirectories
           parent (make-array java.nio.file.attribute.FileAttribute 0))
        destination (assert-isolated-destination!
                     project settings
                     (Files/createTempDirectory
                      parent "semantic-qualification-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-marker! destination)
        isolated-project (project/context (str destination))
        request-limit (:request-provider-document-limit matrix-case)
        concurrency (:request-concurrency-limit matrix-case)
        case-settings
        (assoc settings
               :index-path "."
               :index-name
               (format "llm-context-qualification-%d-%d"
                       request-limit concurrency))
        runtime-state (atom nil)
        started (System/nanoTime)]
    (try
      (let [space (storage/status isolated-project
                                  {:store (:store (config/defaults))}
                                  destination)
            _ (when-not (:safe? space)
                (throw (ex-info "Qualification case lacks storage headroom"
                                {:type :semantic-qualification/unsafe
                                 :resource :storage})))
            launched (runtime/start!
                      isolated-project
                      {:semantic {:lateon-code case-settings}
                       :service (:service (config/defaults))})
            _ (reset! runtime-state launched)
            _ (when-not (= :ready (:status launched))
                (throw (ex-info "Packaged NextPlaid runtime is unavailable"
                                launched)))
            client (:client launched)
            _ (index/ensure-index! client)
            provider-documents (vec (mapcat :chunks documents))
            io-before (process-io (:process launched))
            submit (submit-documents! client provider-documents
                                      request-limit concurrency
                                      (:visibility-timeout-ms settings))
            visible (:visible-documents submit)
            visibility-ms (:visibility-ms submit)
            operation-ms (+ (:submit-ms submit) visibility-ms)
            operation-seconds (/ (max 1.0 operation-ms) 1000.0)
            io-after (process-io (:process launched))
            result
            (merge matrix-case
                   {:status :completed
                    :symbol-jobs (count documents)
                    :provider-documents (count provider-documents)
                    :text-bytes (reduce + 0 (map (comp utf8-size :text)
                                                 provider-documents))
                    :request-count (:request-count submit)
                    :wall-ms (/ (- (System/nanoTime) started) 1000000.0)
                    :operation-ms operation-ms
                    :submit-ms (:submit-ms submit)
                    :visibility-ms visibility-ms
                    :symbols-per-second
                    (/ (double (count documents)) operation-seconds)
                    :provider-documents-per-second
                    (/ (double (count provider-documents)) operation-seconds)
                    :text-bytes-per-second
                    (/ (double (reduce + 0 (map (comp utf8-size :text)
                                                 provider-documents)))
                       operation-seconds)
                    :request-latency-ms
                    {:p50 (percentile (:request-latencies-ms submit) 0.50)
                     :p95 (percentile (:request-latencies-ms submit) 0.95)
                     :max (when (seq (:request-latencies-ms submit))
                            (apply max (:request-latencies-ms submit)))}
                    :provider-rss-bytes (process-rss-bytes (:process launched))
                    :provider-index-bytes (tree-bytes destination)
                    :process-write-bytes
                    (when (and (:write-bytes io-before)
                               (:write-bytes io-after))
                      (max 0 (- (:write-bytes io-after)
                                (:write-bytes io-before))))
                    :duplicate-metadata (- (count visible)
                                           (count provider-documents))
                    :gpu-utilization :unavailable
                    :vram-bytes :unavailable
                    :provider-queue-metrics :unavailable})]
        (runtime/stop! launched)
        (reset! runtime-state nil)
        (delete-tree! destination)
        result)
      (catch Throwable error
        (when-let [launched @runtime-state]
          (try (runtime/stop! launched) (catch Throwable _)))
        (merge matrix-case
               {:status (if (= :semantic-qualification/unsafe
                               (:type (ex-data error)))
                          :unsafe :failed)
                :error-class (.getName (class error))
                :error (.getMessage error)
                ;; This path is emitted only in the local diagnostic result.
                ;; `safe-report` strips it from persisted aggregate evidence.
                :diagnostic-path (str destination)})))))

(defn safe-report
  "Remove source text, identifiers, and absolute diagnostic paths."
  [report]
  (-> report
      (update :cases
              #(mapv (fn [case-result]
                       (dissoc case-result :diagnostic-path)) %))
      (dissoc :sample-symbol-ids :source-paths :documents)))

(defn rank-cases [cases]
  (->> cases
       (filter #(and (= :completed (:status %))
                     (zero? (:duplicate-metadata % 0))))
       (sort-by (juxt #(double (or (:operation-ms %)
                                   (:wall-ms %)
                                   Double/POSITIVE_INFINITY))
                      :request-provider-document-limit
                      :request-concurrency-limit))
       vec))

(defn run-qualification!
  ([project settings sample-size]
   (run-qualification! project settings sample-size matrix))
  ([project settings sample-size cases-to-run]
   (with-open [_lease (lifecycle/acquire! project)]
     (store/with-store [graph project settings]
       (let [prepare-start (System/nanoTime)
             documents (rendered-sample graph project settings sample-size)
             prepare-ms (/ (- (System/nanoTime) prepare-start) 1000000.0)
             _ (when-not (seq documents)
                 (throw
                  (ex-info "No indexable semantic documents were available"
                           {:type :semantic-qualification/empty-sample})))
             sample-summary
             {:symbol-jobs (count documents)
              :prepare-ms prepare-ms
              :provider-documents (reduce + 0 (map (comp count :chunks)
                                                    documents))
              :text-bytes (reduce + 0 (map :sample/text-bytes documents))
              :strata
              (frequencies
               (map (juxt :sample/language :sample/source-role
                          :sample/byte-bucket :sample/chunk-count)
                    documents))}
             cases (mapv #(run-case! project
                                      (get-in settings [:semantic :lateon-code])
                                      documents %)
                         cases-to-run)]
         {:qualification/version 1
          :provider-version
          (get-in settings [:semantic :lateon-code :next-plaid-version])
          :model (get-in settings [:semantic :lateon-code :model])
          :model-revision
          (get-in settings [:semantic :lateon-code :model-revision])
          :document-version
          (get-in settings [:semantic :lateon-code :document-version])
          :sample sample-summary
          :cases cases
          :ranking (mapv #(select-keys
                           % [:request-provider-document-limit
                              :request-concurrency-limit :wall-ms :submit-ms
                              :visibility-ms])
                         (rank-cases cases))})))))

(defn- positive-integer-list [option value]
  (let [values (some->> value
                        (#(str/split % #"," -1))
                        (mapv parse-long))]
    (when-not (and (seq values) (every? pos-int? values))
      (throw (ex-info (str option
                           " requires comma-separated positive integers")
                      {:exit-code 2})))
    (vec (distinct values))))

(defn- parse-args [args]
  (let [[project-path & options] args]
    (when-not project-path
      (throw
       (ex-info
        (str "Usage: clojure -M:qualify-semantic-ingestion PROJECT "
             "[--sample-size N] [--request-batches N,N] "
             "[--request-concurrencies N,N] [--output REPORT.edn]")
        {:exit-code 2})))
    (loop [remaining options
           parsed {:project-path project-path
                   :sample-size 256
                   :request-batches [32 128 300 512]
                   :request-concurrencies [1 2]}]
      (if-let [option (first remaining)]
        (case option
          "--sample-size"
          (let [value (some-> (second remaining) parse-long)]
            (when-not (pos-int? value)
              (throw (ex-info "--sample-size requires a positive integer"
                              {:exit-code 2})))
            (recur (nnext remaining) (assoc parsed :sample-size value)))
          "--request-batches"
          (recur (nnext remaining)
                 (assoc parsed :request-batches
                        (positive-integer-list option (second remaining))))
          "--request-concurrencies"
          (recur (nnext remaining)
                 (assoc parsed :request-concurrencies
                        (positive-integer-list option (second remaining))))
          "--output"
          (if-let [value (second remaining)]
            (recur (nnext remaining) (assoc parsed :output value))
            (throw (ex-info "--output requires a path" {:exit-code 2})))
          (throw (ex-info (str "Unknown qualification option: " option)
                          {:exit-code 2})))
        parsed))))

(defn -main [& args]
  (try
    (let [{:keys [project-path sample-size output request-batches
                  request-concurrencies]} (parse-args args)
          project (project/context project-path)
          settings (config/load-config project)
          cases (qualification-matrix request-batches request-concurrencies)
          report (run-qualification! project settings sample-size cases)
          safe (safe-report report)]
      (when output
        (let [path (absolute (:root project) output)]
          (when-let [parent (.getParent path)]
            (Files/createDirectories
             parent (make-array java.nio.file.attribute.FileAttribute 0)))
          (Files/writeString path (str (pr-str safe) "\n")
                             (make-array OpenOption 0))))
      (pprint/pprint safe))
    (finally
      (shutdown-agents))))
