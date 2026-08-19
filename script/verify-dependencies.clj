(ns verify-dependencies
  "Check that repository dependency declarations agree with the canonical EDN manifest."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [llm-context.model-packages :as model-packages]))

(def root (io/file (System/getProperty "user.dir")))
(def manifest
  (edn/read-string
   (slurp (io/file root "resources/llm_context/dependencies.edn"))))
(def deps (edn/read-string (slurp (io/file root "deps.edn"))))
(def errors (atom []))

(defn check! [condition message]
  (when-not condition
    (swap! errors conj message)))

(defn text [path]
  (try
    (slurp (io/file root path))
    (catch Exception error
      (check! false (str path " could not be read: " (.getMessage error)))
      "")))

(defn contains-literal? [path value]
  (str/includes? (text path) value))

(defn require-literal! [path value description]
  (check! (contains-literal? path value)
          (str description " is missing from " path ": " value)))

(defn json-string [json key]
  (second (re-find (re-pattern (str "\"" (java.util.regex.Pattern/quote key)
                                  "\"\\s*:\\s*\"([^\"]+)\""))
                   json)))

(defn verify-shape! []
  (check! (= 1 (:dependency-contract-version manifest))
          ":dependency-contract-version must be 1")
  (try
    (model-packages/validate! manifest)
    (catch Exception error
      (check! false (str "model package contract is invalid: "
                         (.getMessage error))))))

(defn verify-jvm! []
  (check! (= (get-in manifest [:jvm :deps]) (:deps deps))
          "deps.edn :deps differs from the dependency manifest")
  (check! (= (get-in manifest [:jvm :test-deps])
             (get-in deps [:aliases :test :extra-deps]))
          "deps.edn test dependencies differ from the dependency manifest")
  (check! (= (get-in manifest [:jvm :build-deps])
             (get-in deps [:aliases :build :deps]))
          "deps.edn build dependencies differ from the dependency manifest"))

(defn verify-project-version! []
  (let [version (get-in manifest [:project :version])]
    (doseq [path ["build.clj" "src/llm_context/version.clj"]]
      (require-literal! path (str "\"" version "\"")
                        "project version"))
    (doseq [path ["package.json" "package-lock.json"]]
      (check! (= version (json-string (text path) "version"))
              (str path " version differs from the dependency manifest")))))

(defn verify-toolchain! []
  (let [toolchain (:toolchain manifest)
        min-java (str (:minimum-java-feature toolchain))
        compile-java (str (:compile-java-release toolchain))
        ci-java (str (:ci-java-feature toolchain))
        node (str (:ci-node-major toolchain))]
    (require-literal! "build.clj" (str "\"" compile-java "\"")
                      "Java compile release")
    (doseq [path ["install.sh" "install.ps1"]]
      (require-literal! path min-java "minimum Java feature"))
    (doseq [path [".github/workflows/ci.yml"
                 ".github/workflows/release.yml"
                 ".github/actions/llm-context-action/action.yml"]]
      (require-literal! path (str "java-version: \"" ci-java "\"")
                        "CI Java version")
      (require-literal! path (str "node-version: \"" node "\"")
                        "CI Node version"))
    (doseq [path [".github/workflows/ci.yml" ".github/workflows/release.yml"]]
      (require-literal! path
                        (str "cli: " (:ci-clojure-cli toolchain))
                        "CI Clojure CLI selection"))))

(defn verify-runtime! []
  (let [next-plaid (get-in manifest [:semantic :next-plaid])
        ort (get-in manifest [:semantic :onnx-runtime])
        cuda (get-in manifest [:semantic :cuda])
        janet (get-in manifest [:language :janet])
        grammar (get-in manifest [:native :janet-grammar])
        tree-sitter (get-in manifest [:native :tree-sitter])]
    (doseq [path ["install.sh" "install.ps1" "resources/llm_context/default-config.edn"]]
      (require-literal! path (:version next-plaid) "NextPlaid version")
      (require-literal! path (:revision (get-in manifest [:roles :semantic-retriever]))
                        "LateOn model revision")
      (require-literal! path (:model (get-in manifest [:roles :semantic-retriever]))
                        "LateOn model ID")
      (require-literal! path (:revision (get-in manifest [:roles :query-router-reranker]))
                        "query-router model revision")
      (require-literal! path (:model (get-in manifest [:roles :query-router-reranker]))
                        "query-router model ID"))
    (require-literal! ".github/workflows/release.yml"
                      (:source-revision next-plaid)
                      "NextPlaid source revision")
    (doseq [{:keys [archive sha256]} (:artifacts ort)]
      (require-literal! ".github/workflows/release.yml" archive
                        "ONNX Runtime archive")
      (require-literal! ".github/workflows/release.yml" sha256
                        "ONNX Runtime archive checksum"))
    (doseq [path ["install.sh"]]
      (require-literal! path (:minimum-driver cuda) "minimum NVIDIA driver")
      (require-literal! path (:debian-package cuda) "cuDNN package"))
    (require-literal! "src/llm_context/runtime/setup.clj"
                      "cudnn-package" "cuDNN package binding")
    (doseq [path ["resources/llm_context/janet/catalog-1.41.2.edn"
                 "resources/llm_context/janet/LICENSE.md"]]
      (require-literal! path (:version janet) "Janet catalog version"))
    (doseq [path ["script/build-janet-grammar.sh"
                 "resources/llm_context/native/JANET_GRAMMAR.md"]]
      (require-literal! path (:revision grammar) "Janet grammar revision")
      (require-literal! path (:version tree-sitter) "Tree-sitter version"))
    (require-literal! "script/build-janet-grammar.sh"
                      (:archive-sha256 grammar)
                      "Janet grammar archive checksum")
    (require-literal! "script/build-janet-grammar.sh"
                      (:archive-sha256 tree-sitter)
                      "Tree-sitter archive checksum")
    (require-literal! "resources/llm_context/native/JANET_GRAMMAR.md"
                      (:zig-minimum-version grammar)
                      "minimum Zig version")))

(defn verify-model-hashes! []
  (doseq [[role package] (:roles manifest)
          [filename sha256] (:files package)
          :when (contains? #{:semantic-retriever :query-router-reranker} role)
          path ["install.sh" "install.ps1"]]
    (require-literal! path sha256
                      (str (name role) " " filename " checksum"))))

(defn github-files []
  (filter #(.isFile ^java.io.File %)
          (file-seq (io/file root ".github"))))

(defn require-github-literal! [value description]
  (check! (some #(str/includes? (slurp ^java.io.File %) value)
                (github-files))
          (str description " is missing from .github: " value)))

(defn verify-actions! []
  (let [actions (get-in manifest [:github :actions])]
    (doseq [[action version] actions]
      (let [qualified (case action
                        "setup-clojure" "DeLaGuardo/setup-clojure"
                        "setup-zig" "mlugg/setup-zig"
                        "rust-toolchain" "dtolnay/rust-toolchain"
                        "rust-cache" "Swatinem/rust-cache"
                        (str "actions/" action))]
        (require-github-literal! (str qualified "@" version)
                                 "GitHub Action pin")))
    (require-literal! ".github/actions/llm-context-action/action.yml"
                      (str "v" (get-in manifest [:github :composite-action-release]))
                      "composite action release")))

(defn verify-npm! []
  (let [package-json (text "package.json")]
    (check! (not (re-find #"(?m)^\s*\"(?:dependencies|devDependencies)\"\s*:"
                          package-json))
            "package.json unexpectedly declares npm dependencies; update the manifest if this changes")))

(defn verify-removed-mirror! []
  (doseq [path ["README.md" "docs/user-guide.md" "src" "test"]]
    (let [file (io/file root path)]
      (when (.exists file)
        (doseq [candidate (if (.isDirectory file) (file-seq file) [file])
                :when (and (.isFile ^java.io.File candidate)
                           (re-find #"\.(clj|edn|md)$" (.getName ^java.io.File candidate)))]
          (check! (not (str/includes? (slurp candidate) "llm_context/model-packages.edn"))
                  (str "removed model manifest path remains in " (.getPath ^java.io.File candidate))))))))

(defn -main [& _]
  (verify-shape!)
  (verify-jvm!)
  (verify-project-version!)
  (verify-toolchain!)
  (verify-runtime!)
  (verify-model-hashes!)
  (verify-actions!)
  (verify-npm!)
  (verify-removed-mirror!)
  (if (seq @errors)
    (do
      (binding [*out* *err*]
        (println "Dependency manifest verification failed:")
        (doseq [error @errors]
          (println "-" error)))
      (System/exit 1))
    (println "Dependency manifest verified:"
             "all declared JVM, runtime, native, model, installer, and CI pins agree.")))

(apply -main *command-line-args*)
