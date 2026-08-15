(ns llm-context.context-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.context :as context]
            [llm-context.model.ids :as ids]
            [llm-context.project :as project]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(deftest intent-focus-selects-one-seed-and-retains-bounded-alternatives
  (let [results
        (mapv (fn [index]
                {:id (str "symbol:" index)
                 :qualified-name (str "fixture/symbol-" index)
                 :source-role (if (zero? index) :production :test)
                 :fused-rank (inc index)
                 :final-rank (inc index)
                 :matched-by (if (zero? index) #{:lateon :fts} #{:lateon})
                 :score (/ 1.0 (inc index))})
              (range 7))
        retrieval {:status :ok :latency-ms 12}
        resolution
        (context/resolve-intent-focus
         "where is retry handled?"
         {:results results :retrieval retrieval})]
    (is (= :intent (:mode resolution)))
    (is (= :hybrid (:strategy resolution)))
    (is (= ["symbol:0"] (mapv :id (:selected resolution))))
    (is (= :production (get-in resolution [:selected 0 :source-role])))
    (is (= ["symbol:1" "symbol:2" "symbol:3" "symbol:4"]
           (mapv :id (:alternatives resolution))))
    (is (= retrieval (:retrieval resolution))))
  (is (= :lexical-fallback
         (:strategy
          (context/resolve-intent-focus
           "retry"
           {:results [{:id "symbol:retry"
                       :qualified-name "fixture/retry"
                       :matched-by #{:fts}
                       :score 0.1}]
            :retrieval {:status :unavailable}}))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"No symbol matches context intent"
       (context/resolve-intent-focus
        "missing intent"
        {:results [] :retrieval {:status :no-matches}}))))

(deftest intent-focus-selects-diverse-roots-for-set-plans
  (let [results [{:id "symbol:a" :qualified-name "fixture.a/routes"
                  :file "src/a.clj" :intent-score 4 :intent-qualified? true
                  :matched-by #{:lateon}}
                 {:id "symbol:a-helper" :qualified-name "fixture.a/handler"
                  :file "src/a.clj" :intent-score 3 :intent-qualified? true
                  :matched-by #{:lateon}}
                 {:id "symbol:b" :qualified-name "fixture.b/routes"
                  :file "src/b.clj" :intent-score 2 :intent-qualified? true
                  :matched-by #{:fts}}]
        resolution
        (context/resolve-intent-focus
         "what modules expose endpoints?"
         {:results results
          :retrieval {:query-plan {:shape :set :seed-mode :multi
                                   :max-seeds 2}}})]
    (is (= ["symbol:a" "symbol:b"] (mapv :id (:selected resolution))))
    (is (= ["fixture.a/routes" "fixture.a/handler" "fixture.b/routes"]
           (mapv :qualified-name (:inventory resolution))))
    (is (= ["symbol:a-helper"] (mapv :id (:alternatives resolution))))))

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
                       :symbol/platform :clj :symbol/analyzer :test
                       :symbol/scope :scope/top-level
                       :symbol/role :role/definition
                       :symbol/indexable? true
                       :symbol/signature (str "(defn " name " [])")
                       :source/start-line line :source/start-column 1
                       :source/end-line line :source/end-column 12})
        symbols [(make-symbol "symbol:a" "a" 1) (make-symbol "symbol:b" "b" 2)
                 (make-symbol "symbol:c" "c" 3)]
        edge (fn [id from to target line]
               {:entity/type :entity.type/edge :edge/id id :edge/kind :edge.kind/calls
                :edge/from from :edge/to to :edge/target-text target
                :edge/resolution :resolution/exact :edge/confidence 1.0
                :edge/evidence :test-exact
                :source/start-line line :source/start-column 1
                :source/end-line line :source/end-column 5})
        entities (concat symbols [(edge "edge:ab" "symbol:a" "symbol:b" "b" 1)
                                  (edge "edge:bc" "symbol:b" "symbol:c" "c" 2)])]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file entities)
      (let [packet (context/build graph {:focus "a" :depth 1 :max-tokens 1000})]
        (is (= 3 (:packet/version packet)))
        (is (= :exact (get-in packet [:focus-resolution :strategy])))
        (is (= ["symbol:a"]
               (mapv :id (get-in packet [:focus-resolution :selected]))))
        (is (= #{"symbol:a" "symbol:b"} (set (map :id (:symbols packet)))))
        (is (not-any? #(= "symbol:c" (:id %)) (:symbols packet)))
        (is (re-find #"Code context: a" (context/markdown packet)))
        (is (re-find #"Focus resolution: exact"
                     (context/markdown packet)))
        (is (<= (get-in packet [:budget :estimated-tokens]) 1000)))
      (let [resolution
            {:mode :intent
             :strategy :hybrid
             :selected [{:id "symbol:a" :rank 1 :matched-by #{:lateon}}]
             :alternatives [{:id "symbol:c" :rank 2
                             :matched-by #{:lateon}}]}
            packet
            (context/build-from-seeds
             graph {:focus "entry behavior" :depth 1 :max-tokens 1000}
             resolution)]
        (is (= resolution (:focus-resolution packet)))
        (is (= #{"symbol:a" "symbol:b"} (set (map :id (:symbols packet)))))
        (is (not-any? #(= "symbol:c" (:id %)) (:symbols packet))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"unknown symbols"
           (context/build-from-seeds
            graph {:focus "missing" :depth 1 :max-tokens 1000}
            {:mode :intent :strategy :hybrid
             :selected [{:id "symbol:missing"}]
             :alternatives []}))))))

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
                  :symbol/platform :clj :symbol/analyzer :test
                  :symbol/scope :scope/top-level
                  :symbol/role :role/definition
                  :symbol/indexable? true
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
              :edge/evidence :test-exact
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
                  :symbol/platform :clj :symbol/analyzer :test
                  :symbol/scope :scope/top-level
                  :symbol/role :role/definition
                  :symbol/indexable? true
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
                       :edge/evidence :test-exact
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

(deftest typed-topic-bridges-connect-dispatchers-to-handlers
  (let [root (Files/createTempDirectory
              "llm-context-topic-path-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        file {:entity/type :entity.type/file :file/id "file:events.cljs"
              :file/path "events.cljs"
              :file/language :language/clojurescript
              :file/content-hash (ids/content-hash "events")
              :file/size 6 :file/modified-at 1}
        symbol (fn [id name line]
                 {:entity/type :entity.type/symbol
                  :symbol/id id :symbol/name name
                  :symbol/qualified-name (str "events/" name)
                  :symbol/kind :symbol.kind/function
                  :symbol/file (:file/id file)
                  :symbol/platform :cljs :symbol/analyzer :test
                  :symbol/scope :scope/top-level
                  :symbol/role :role/definition
                  :symbol/indexable? true
                  :source/start-line line :source/start-column 1
                  :source/end-line line :source/end-column 8})
        dispatcher (symbol "symbol:dispatch" "dispatch!" 1)
        handler (symbol "symbol:handler" "handler" 10)
        noise (symbol "symbol:finalize" "finalize-batch!" 20)
        topic {:entity/type :entity.type/topic :topic/id "topic:saved"
               :topic/kind :event :topic/key ":saved"
               :topic/platform :cljs}
        edge (fn [id kind from line]
               {:entity/type :entity.type/edge :edge/id id :edge/kind kind
                :edge/from from :edge/to (:topic/id topic)
                :edge/target-text ":saved"
                :edge/resolution :resolution/exact :edge/confidence 1.0
                :edge/evidence :test-topic
                :source/start-line line :source/start-column 1
                :source/end-line line :source/end-column 5})]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file!
       graph file
       [dispatcher handler noise topic
        (edge "edge:dispatch-topic" :edge.kind/event-dispatches
              (:symbol/id dispatcher) 2)
        (edge "edge:register-topic" :edge.kind/topic-registers
              (:symbol/id handler) 11)])
      (let [packet (context/build
                    graph {:focus "symbol:dispatch"
                           :depth 2 :max-tokens 1200})
            by-id (into {} (map (juxt :id identity)) (:symbols packet))]
        (is (contains? by-id "symbol:handler"))
        (is (not (contains? by-id "symbol:finalize")))
        (is (= 1.0 (:path-cost (get by-id "symbol:handler"))))
        (is (= [:edge.kind/event-dispatches :edge.kind/topic-registers]
               (mapv :kind
                     (:selected-path (get by-id "symbol:handler")))))
        (is (= ":saved" (:key (first (:topics packet)))))
        (is (false? (get-in packet [:truncation :graph?])))))))
