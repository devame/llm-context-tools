(ns llm-context.context-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.context :as context]
            [llm-context.model.ids :as ids]
            [llm-context.project :as project]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(deftest context-packets-are-focused-depth-bounded-and-renderable
  (let [root (Files/createTempDirectory "llm-context-packet-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        file {:entity/type :entity.type/file :file/id "file:chain.clj"
              :file/path "chain.clj" :file/language :language/clojure
              :file/content-hash (ids/content-hash "chain")
              :file/size 5 :file/modified-at 1}
        make-symbol (fn [id name line]
                      {:entity/type :entity.type/symbol :symbol/id id :symbol/name name
                       :symbol/qualified-name (str "chain/" name)
                       :symbol/kind :symbol.kind/function :symbol/file (:file/id file)
                       :symbol/signature (str "(defn " name " [])")
                       :source/start-line line :source/start-column 1
                       :source/end-line line :source/end-column 12})
        symbols [(make-symbol "symbol:a" "a" 1) (make-symbol "symbol:b" "b" 2)
                 (make-symbol "symbol:c" "c" 3)]
        edge (fn [id from to target line]
               {:entity/type :entity.type/edge :edge/id id :edge/kind :edge.kind/calls
                :edge/from from :edge/to to :edge/target-text target
                :edge/resolution :resolution/exact :edge/confidence 1.0
                :source/start-line line :source/start-column 1
                :source/end-line line :source/end-column 5})
        entities (concat symbols [(edge "edge:ab" "symbol:a" "symbol:b" "b" 1)
                                  (edge "edge:bc" "symbol:b" "symbol:c" "c" 2)])]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file entities)
      (let [packet (context/build graph {:focus "a" :depth 1 :max-tokens 1000})]
        (is (= #{"symbol:a" "symbol:b"} (set (map :id (:symbols packet)))))
        (is (not-any? #(= "symbol:c" (:id %)) (:symbols packet)))
        (is (re-find #"Code context: a" (context/markdown packet)))
        (is (<= (get-in packet [:budget :estimated-tokens]) 1000))))))

(deftest disconnected-symbols-do-not-enter-focused-context
  (let [root (Files/createTempDirectory
              "llm-context-packet-scaling-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        file {:entity/type :entity.type/file :file/id "file:large.clj"
              :file/path "large.clj" :file/language :language/clojure
              :file/content-hash (ids/content-hash "large")
              :file/size 5 :file/modified-at 1}
        symbol (fn [id name line]
                 {:entity/type :entity.type/symbol
                  :symbol/id id :symbol/name name
                  :symbol/qualified-name (str "large/" name)
                  :symbol/kind :symbol.kind/function
                  :symbol/file (:file/id file)
                  :source/start-line line :source/start-column 1
                  :source/end-line line :source/end-column 5})
        focus (symbol "symbol:focus" "focus" 1)
        neighbor (symbol "symbol:neighbor" "neighbor" 2)
        noise (mapv (fn [index]
                      (symbol (str "symbol:noise-" index)
                              (str "noise-" index)
                              (+ index 10)))
                    (range 1000))
        edge {:entity/type :entity.type/edge :edge/id "edge:focus-neighbor"
              :edge/kind :edge.kind/calls
              :edge/from (:symbol/id focus) :edge/to (:symbol/id neighbor)
              :edge/target-text "neighbor"
              :edge/resolution :resolution/exact :edge/confidence 1.0
              :source/start-line 1 :source/start-column 1
              :source/end-line 1 :source/end-column 5}]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file (into [focus neighbor edge] noise))
      (let [packet (context/build
                    graph {:focus "symbol:focus"
                           :depth 1 :max-tokens 1000})]
        (is (= #{"symbol:focus" "symbol:neighbor"}
               (set (map :id (:symbols packet)))))
        (is (= ["edge:focus-neighbor"]
               (mapv :id (:relationships packet))))))))

(deftest high-degree-context-traversal-is-capped-before-pulling-records
  (let [root (Files/createTempDirectory
              "llm-context-packet-fanout-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        file {:entity/type :entity.type/file :file/id "file:fanout.clj"
              :file/path "fanout.clj" :file/language :language/clojure
              :file/content-hash (ids/content-hash "fanout")
              :file/size 6 :file/modified-at 1}
        symbol (fn [id name line]
                 {:entity/type :entity.type/symbol
                  :symbol/id id :symbol/name name
                  :symbol/qualified-name (str "fanout/" name)
                  :symbol/kind :symbol.kind/function
                  :symbol/file (:file/id file)
                  :source/start-line line :source/start-column 1
                  :source/end-line line :source/end-column 5})
        focus (symbol "symbol:focus" "focus" 1)
        neighbors (mapv (fn [index]
                          (symbol (str "symbol:neighbor-" index)
                                  (str "neighbor-" index)
                                  (+ index 2)))
                        (range 200))
        edges (mapv (fn [index neighbor]
                      {:entity/type :entity.type/edge
                       :edge/id (str "edge:fanout-" index)
                       :edge/kind :edge.kind/calls
                       :edge/from (:symbol/id focus)
                       :edge/to (:symbol/id neighbor)
                       :edge/target-text (:symbol/name neighbor)
                       :edge/resolution :resolution/exact
                       :edge/confidence 1.0
                       :source/start-line 1 :source/start-column 1
                       :source/end-line 1 :source/end-column 5})
                    (range) neighbors)]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file (into [focus] (concat neighbors edges)))
      (let [packet (context/build
                    graph {:focus "symbol:focus"
                           :depth 1 :max-tokens 400})]
        ;; The traversal ceiling is derived from the token budget (400 / 8),
        ;; so a hub cannot cause all 200 adjacent records to be pulled first.
        (is (<= (count (:symbols packet)) 50))
        (is (:truncated? packet))
        (is (<= (get-in packet [:budget :estimated-tokens]) 400))))))
