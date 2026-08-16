(ns llm-context.model-packages-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [llm-context.model-packages :as packages]))

(defn- delete-tree! [file]
  (when (.exists (io/file file))
    (doseq [entry (reverse (file-seq (io/file file)))]
      (.delete entry))))

(deftest custom-manifests-require-and-enforce-a-checksum
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "llm-context-model-manifest" (make-array java.nio.file.attribute.FileAttribute 0)))
        manifest (io/file root "models.edn")]
    (try
      (spit manifest (pr-str (packages/defaults)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires --manifest-sha256"
                            (packages/load-manifest {:manifest (.getPath manifest)})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"checksum verification failed"
                            (packages/load-manifest
                             {:manifest (.getPath manifest)
                              :manifest-sha256 (apply str (repeat 64 "0"))})))
      (is (= 1 (:contract-version
                (packages/load-manifest
                 {:manifest (.getPath manifest)
                  :manifest-sha256 (packages/sha256 manifest)}))))
      (finally (delete-tree! root)))))

(deftest installs-local-verified-packages-and-writes-runtime-registry
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "llm-context-model-install" (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file root "source")
        model-file (io/file source "tiny.onnx")
        manifest-file (io/file root "models.edn")
        cache (io/file root "cache")
        registry-file (io/file root "installed.edn")]
    (try
      (.mkdirs source)
      (spit model-file "verified model bytes")
      (let [manifest
            {:contract-version 1
             :roles
             {:semantic-retriever
              {:model "example/semantic"
               :revision "0123456789abcdef0123456789abcdef01234567"
               :format :next-plaid-onnx
               :entrypoint "tiny.onnx"
               :base-url "https://invalid.example"
               :files {"tiny.onnx" (packages/sha256 model-file)}}}}]
        (spit manifest-file (pr-str manifest))
        (let [installed
              (packages/install!
               {:manifest (.getPath manifest-file)
                :manifest-sha256 (packages/sha256 manifest-file)
                :cache (.getPath cache)
                :registry (.getPath registry-file)
                :selected-roles [:semantic-retriever]
                :source-roots {:semantic-retriever (.getPath source)}})
              registry (edn/read-string (slurp registry-file))
              overlay (packages/config-overlay registry)]
          (is (= "example/semantic"
                 (get-in installed [:roles :semantic-retriever :model])))
          (is (= "example/semantic"
                 (get-in overlay [:semantic :lateon-code :model])))
          (is (.isFile (io/file (get-in registry [:roles :semantic-retriever :path])
                                "tiny.onnx")))))
      (finally (delete-tree! root)))))

(deftest rejects-model-content-that-does-not-match-the-manifest
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "llm-context-model-reject" (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file root "source")
        manifest-file (io/file root "models.edn")]
    (try
      (.mkdirs source)
      (spit (io/file source "model.gguf") "tampered")
      (spit manifest-file
            (pr-str
             {:contract-version 1
              :roles
              {:answer-reader
               {:model "example/answer"
                :revision "0123456789abcdef0123456789abcdef01234567"
                :format :gguf
                :entrypoint "model.gguf"
                :base-url "https://invalid.example"
                :files {"model.gguf" (apply str (repeat 64 "a"))}}}}))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"checksum failed"
           (packages/install!
            {:manifest (.getPath manifest-file)
             :manifest-sha256 (packages/sha256 manifest-file)
             :cache (.getPath (io/file root "cache"))
             :selected-roles [:answer-reader]
             :source-roots {:answer-reader (.getPath source)}})))
      (finally (delete-tree! root)))))
