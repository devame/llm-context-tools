(ns llm-context.semantic.document-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.model.ids :as ids]
            [llm-context.project :as project]
            [llm-context.semantic.document :as document]
            [llm-context.store :as store]
            [llm-context.store-test :as fixture])
  (:import [java.nio.file Files]))

(def lateon
  (get-in (config/defaults) [:semantic :lateon-code]))

(defn file [path source language]
  {:entity/type :entity.type/file
   :file/id (ids/file-id path)
   :file/path path
   :file/language language
   :file/content-hash (ids/content-hash source)
   :file/size (count (.getBytes source java.nio.charset.StandardCharsets/UTF_8))
   :file/modified-at 1})

(defn symbol-entity [file id name start-line start-column end-line end-column]
  {:entity/type :entity.type/symbol
   :symbol/id id
   :symbol/name name
   :symbol/qualified-name (str "sample/" name)
   :symbol/kind :symbol.kind/function
   :symbol/file (:file/id file)
   :symbol/signature (str "(defn " name " [])")
   :source/start-line start-line
   :source/start-column start-column
   :source/end-line end-line
   :source/end-column end-column})

(deftest extracts-one-based-utf8-byte-ranges
  (let [source "ignored\n(defn café []\n  :ok)\ntrailing"]
    (is (= "(defn café []\n  :ok)"
           (document/extract-range
            source
            {:source/start-line 2 :source/start-column 1
             :source/end-line 3 :source/end-column 7}))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"exceeds file line count"
       (document/extract-range
        "one line"
        {:source/start-line 2 :source/start-column 1
         :source/end-line 2 :source/end-column 2}))))

(deftest document-text-and-hash-are-deterministic
  (let [source "(defn fetch [url]\n  (retry #(http/get url)))"
        file (file "src/http.clj" source :language/clojure)
        symbol (symbol-entity file "symbol:fetch" "fetch" 1 1 2 27)
        relationships [{:kind :edge.kind/calls :target "retry"}
                       {:kind :edge.kind/calls :target "http/get"}
                       {:kind :edge.kind/calls :target "retry"}]
        first (document/build lateon symbol file source relationships)
        second (document/build lateon symbol file source (reverse relationships))
        text (get-in first [:chunks 0 :text])]
    (is (= first second))
    (is (str/includes? text "Language: clojure"))
    (is (str/includes? text "Qualified name: sample/fetch"))
    (is (str/includes? text "Calls: http/get, retry"))
    (is (str/includes? text source))
    (is (= (:document-hash first)
           (get-in first [:chunks 0 :document-hash])))))

(deftest model-and-format-identity-change-the-document-hash
  (let [source "(defn a [] 1)"
        file (file "src/a.clj" source :language/clojure)
        symbol (symbol-entity file "symbol:a" "a" 1 1 1 14)
        base (document/build lateon symbol file source [])
        changed-model (document/build (assoc lateon :model-revision
                                             (apply str (repeat 40 "a")))
                                      symbol file source [])
        changed-format (document/build (update lateon :document-version inc)
                                       symbol file source [])]
    (is (not= (:document-hash base) (:document-hash changed-model)))
    (is (not= (:document-hash base) (:document-hash changed-format)))))

(deftest oversized-symbols-become-overlapping-bounded-chunks
  (let [lines (map #(str "  (println \"line-" % "\")\n") (range 30))
        source (str "(defn verbose []\n" (apply str lines) ")")
        file (file "src/verbose.clj" source :language/clojure)
        symbol (symbol-entity file "symbol:verbose" "verbose"
                              1 1 32 2)
        settings (assoc lateon :max-document-bytes 300
                        :chunk-overlap-lines 2)
        built (document/build settings symbol file source [])
        chunks (:chunks built)]
    (is (< 1 (count chunks)))
    (is (every? #(<= (count (.getBytes ^String (:text %)
                                      java.nio.charset.StandardCharsets/UTF_8))
                     (:max-document-bytes settings))
                chunks))
    (is (= (count chunks) (:chunk-count (first chunks))))
    (is (str/includes? (:text (second chunks)) "Chunk: 2/"))))

(deftest build-file-verifies-source-hash-and-skips-synthetic-modules
  (let [root (Files/createTempDirectory
              "llm-context-semantic-document-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        source "(defn useful []\n  (println :ok))"
        path (.resolve root "src/app.clj")
        file (file "src/app.clj" source :language/clojure)
        function (symbol-entity file "symbol:useful" "useful" 1 1 2 17)
        module (assoc (symbol-entity file "symbol:module" "app" 1 1 2 17)
                      :symbol/kind :symbol.kind/module)]
    (Files/createDirectories (.getParent path)
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path) source)
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file [module function])
      (let [result (document/build-file graph project lateon (:file/id file))]
        (is (= :ready (:status result)))
        (is (= ["symbol:useful"] (mapv :symbol-id (:documents result)))))
      (spit (str path) "(defn changed [] :new)")
      (let [result (document/build-file graph project lateon (:file/id file))]
        (is (= :source-changed (:status result)))
        (is (empty? (:documents result)))))))

(deftest build-file-reports-deleted-files
  (let [project (fixture/temp-project)]
    (store/with-store [graph project (config/defaults)]
      (is (= {:status :deleted :file-id "file:missing"
              :documents [] :diagnostics []}
             (document/build-file graph project lateon "file:missing"))))))

(deftest build-file-uses-the-same-replacement-decoding-as-analysis
  (let [root (Files/createTempDirectory
              "llm-context-malformed-document-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        path (.resolve root "src/app.clj")
        bytes (byte-array
               (concat (.getBytes "(defn useful [] \""
                                  java.nio.charset.StandardCharsets/UTF_8)
                       [(unchecked-byte 0xc4)]
                       (.getBytes "\")"
                                  java.nio.charset.StandardCharsets/UTF_8)))
        source "(defn useful [] \"\uFFFD\")"
        file (file "src/app.clj" source :language/clojure)
        function (symbol-entity file "symbol:useful" "useful" 1 1 1 20)]
    (Files/createDirectories (.getParent path)
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/write path bytes (make-array java.nio.file.OpenOption 0))
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file [function])
      (let [result (document/build-file graph project lateon (:file/id file))]
        (is (= :ready (:status result)))
        (is (= 1 (count (:documents result))))
        (is (str/includes? (get-in result [:documents 0 :chunks 0 :text])
                           "\uFFFD"))))))
