(ns check-latest-dependencies
  "Compare the verified dependency contract with current upstream releases.

  This intentionally reports stale pins instead of mutating the manifest. A
  version bump must still pass the repository's build and compatibility tests.
  Use --offline only when network access is unavailable; the installer does
  not use that mode by default."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(def root (io/file (System/getProperty "user.dir")))
(def manifest (edn/read-string (slurp (io/file root "resources/llm_context/dependencies.edn"))))
(def failures (atom []))
(def client (-> (HttpClient/newBuilder)
                (.connectTimeout (Duration/ofSeconds 20))
                (.build)))

(defn- progress! [completed total label]
  (let [width 28
        filled (int (* width (/ (double completed) total)))
        bar (str (apply str (repeat filled "#"))
                 (apply str (repeat (- width filled) ".")))]
    (printf "\r[%s] %d/%d %s" bar completed total label)
    (flush)
    (when (= completed total)
      (println))))

(defn- fail! [message]
  (swap! failures conj message)
  (println (str "\nERROR: " message)))

(defn- normalize-version [value]
  (-> (str value) str/trim (str/replace-first #"^v" "")))

(defn- http-json [url]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.timeout (Duration/ofSeconds 45))
                    (.header "Accept" "application/json")
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info (str "HTTP " (.statusCode response) " from " url)
                      {:url url :status (.statusCode response)})))
    (json/read-str (.body response) :key-fn keyword)))

(defn- gh-json [endpoint]
  (let [{:keys [exit out err]} (shell/sh "gh" "api" endpoint)]
    (when-not (zero? exit)
      (throw (ex-info (str "gh api failed for " endpoint ": " (str/trim err))
                      {:exit exit :endpoint endpoint})))
    (json/read-str out :key-fn keyword)))

(defn- github-release-check [label repository expected]
  (let [actual (:tag_name (gh-json (str "repos/" repository "/releases/latest")))]
    (if (= (normalize-version expected) (normalize-version actual))
      (println (format "\nOK: %-28s %s" label actual))
      (fail! (format "%s is %s upstream; manifest pins %s"
                     label actual expected)))))

(defn- maven-release [group artifact]
  (try
    (let [metadata (slurp (str "https://repo.maven.apache.org/maven2/"
                               (str/replace group "." "/") "/" artifact "/maven-metadata.xml"))]
      (or (second (re-find #"<release>\s*([^<]+)\s*</release>" metadata))
          (second (re-find #"<latest>\s*([^<]+)\s*</latest>" metadata))
          (throw (ex-info (str "Maven metadata has no release for " group "/" artifact)
                          {:group group :artifact artifact}))))
    (catch Exception error
      (if (= group "org.datalevin")
        (:latest_version
         (http-json (str "https://clojars.org/api/artifacts/" group "/" artifact)))
        (throw error)))))

(defn- maven-check [label group artifact path]
  (let [expected (get-in manifest (conj path :mvn/version))
        actual (maven-release group artifact)]
    (if (= (normalize-version expected) (normalize-version actual))
      (println (format "\nOK: %-28s %s" label actual))
      (fail! (format "%s is %s on Maven Central; manifest pins %s"
                     label actual expected)))))

(defn- clojure-stable-check []
  (let [html (slurp "https://clojure.org/releases")
        actual (second (re-find #"Clojure\s+([0-9]+\.[0-9]+\.[0-9]+)" html))
        expected (get-in manifest [:jvm :deps 'org.clojure/clojure :mvn/version])]
    (when-not actual
      (throw (ex-info "Clojure releases page did not expose a stable version" {})))
    (if (= expected actual)
      (println (format "\nOK: %-28s %s" "Clojure stable" actual))
      (fail! (format "Clojure stable is %s upstream; manifest pins %s"
                     actual expected)))))

(defn- node-lts-check []
  (let [versions (http-json "https://nodejs.org/dist/index.json")
        latest (first (filter #(string? (:lts %)) versions))
        expected (str (get-in manifest [:toolchain :ci-node-major]))
        actual (some-> (:version latest) (str/replace-first #"^v" "") (str/split #"\.") first)]
    (when-not latest
      (throw (ex-info "Node.js published no LTS release" {})))
    (if (= expected actual)
      (println (format "\nOK: %-28s %s (%s)" "Node.js LTS major" (:version latest) (:lts latest)))
      (fail! (format "Node.js LTS major is %s upstream; CI manifest pins %s"
                     actual expected)))))

(defn- stable-version-key [value]
  (let [text (if (keyword? value) (name value) (str value))]
    (mapv #(Long/parseLong %) (str/split (normalize-version text) #"\."))))

(defn- zig-stable-check []
  (let [index (http-json "https://ziglang.org/download/index.json")
        candidates (->> index
                        (keep (fn [[version release]]
                                (when (and (re-matches #"\d+\.\d+\.\d+" (name version))
                                           (:x86_64-linux release))
                                  [version release])))
                        (sort-by (comp stable-version-key first)))
        [version release] (last candidates)
        actual (some-> version name)
        expected (get-in manifest [:native :janet-grammar :zig-minimum-version])
        binary (:x86_64-linux release)]
    (when-not (and actual binary)
      (throw (ex-info "Zig published no stable x86_64 Linux binary" {})))
    (if (and (not (neg? (compare (stable-version-key actual)
                                 (stable-version-key expected))))
             (string? (:tarball binary))
             (string? (:shasum binary)))
      (println (format "\nOK: %-28s %s (official Linux binary)" "Zig" actual))
      (fail! (format "Zig stable binary is %s upstream; minimum is %s"
                     actual expected)))))

(defn- model-check [role]
  (let [package (get-in manifest [:roles role])
        model (:model package)
        actual (:sha (http-json (str "https://huggingface.co/api/models/" model)))
        expected (:revision package)]
    (if (= expected actual)
      (println (format "\nOK: %-28s %s" (str (name role) " revision") actual))
      (fail! (format "%s latest revision is %s; manifest pins %s"
                     model actual expected)))))

(defn- checks []
  (let [actions (get-in manifest [:github :actions])]
    (concat
     [["ONNX Runtime" #(github-release-check
                         "ONNX Runtime"
                         "microsoft/onnxruntime"
                         (str "v" (get-in manifest [:semantic :onnx-runtime :version])))]
      ["NextPlaid" #(github-release-check
                      "NextPlaid"
                      "lightonai/next-plaid"
                      (str "v" (get-in manifest [:semantic :next-plaid :version])))]
      ["Tree-sitter core" #(github-release-check
                             "Tree-sitter core"
                             "tree-sitter/tree-sitter"
                             (str "v" (get-in manifest [:native :tree-sitter :version])))]
      ["Zig" zig-stable-check]
      ["clj-kondo" #(github-release-check
                      "clj-kondo" "clj-kondo/clj-kondo"
                      (str "v" (get-in manifest [:jvm :deps 'clj-kondo/clj-kondo :mvn/version])))]
      ["Rust toolchain" #(github-release-check
                          "Rust toolchain" "rust-lang/rust"
                          (get-in manifest [:github :actions "rust-toolchain"]))]]
     (for [[action version] actions
           :let [repository (case action
                              "setup-clojure" "DeLaGuardo/setup-clojure"
                              "setup-zig" "mlugg/setup-zig"
                              "rust-toolchain" "dtolnay/rust-toolchain"
                              "rust-cache" "Swatinem/rust-cache"
                              (str "actions/" action))]
           :when (not= action "rust-toolchain")]
       [(str "Action " action)
        #(github-release-check (str "Action " action) repository version)])
     [["data.json" #(maven-check "data.json" "org.clojure" "data.json"
                                  [:jvm :deps 'org.clojure/data.json])]
      ["Clojure stable" clojure-stable-check]
      ["tools.reader" #(maven-check "tools.reader" "org.clojure" "tools.reader"
                                     [:jvm :deps 'org.clojure/tools.reader])]
      ["Datalevin" #(maven-check "Datalevin" "org.datalevin" "datalevin-embedded"
                                  [:jvm :deps 'org.datalevin/datalevin-embedded])]
      ["JTreeSitter" #(maven-check "JTreeSitter" "io.github.tree-sitter" "jtreesitter"
                                    [:jvm :deps 'io.github.tree-sitter/jtreesitter])]
      ["Tree-sitter Java" #(maven-check "Tree-sitter Java" "io.github.bonede" "tree-sitter"
                                         [:jvm :deps 'io.github.bonede/tree-sitter])]
      ["tools.build" #(maven-check "tools.build" "io.github.clojure" "tools.build"
                                    [:jvm :build-deps 'io.github.clojure/tools.build])]
      ["Node.js LTS" node-lts-check]
      ["LateOn-Code" #(model-check :semantic-retriever)]
      ["query router" #(model-check :query-router-reranker)]
      ["answer reader" #(model-check :answer-reader)]])))

(defn -main [& args]
  (if (some #{"--offline"} args)
    (println "Online dependency check skipped (--offline).")
    (let [all-checks (vec (checks))
          total (count all-checks)]
      (doseq [[index [label check]] (map-indexed vector all-checks)]
        (progress! (inc index) total label)
        (try
          (check)
          (catch Exception error
            (fail! (str label " could not be checked: " (.getMessage error))))))
      (if (seq @failures)
        (do
          (println "\nLatest dependency check failed:")
          (doseq [failure @failures]
            (println " -" failure))
          (System/exit 1))
        (do
          (println "Latest dependency check passed: all checked pins match upstream.")
          (System/exit 0))))))

(apply -main *command-line-args*)
