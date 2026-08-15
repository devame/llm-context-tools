(ns llm-context.intent-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.intent :as intent]))

(deftest automatic-query-plans-start-shape-neutral
  (is (= :adaptive (:shape (intent/analyze "how to reset password"))))
  (is (= :multi (:seed-mode (intent/analyze "what modules expose HTTP endpoints?"))))
  (is (= :shape-neutral-retrieval
         (:reason (intent/analyze "unusual wording the old rules never knew"))))
  (is (= :lookup (:shape (intent/analyze
                          "what modules expose HTTP endpoints?"
                          {:seed-mode :single}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Seed mode"
                        (intent/normalize-seed-mode "everything"))))

(deftest advisory-shapes-require-structural-support
  (let [plan (intent/analyze "show the relevant code")
        advice {:provider :mixedbread-32m :status :available
                :suggested-shape :flow :scores {:flow 9.0 :set 8.0}
                :margin 1.0}
        candidates [{:id "a" :intent-qualified? true
                     :intent-reasons [:query-term-handler]}
                    {:id "b" :intent-qualified? true
                     :intent-reasons [:query-term-handler]}]
        supported (intent/resolve-plan
                   plan candidates
                   {:advisory advice :exact-relationship-count 1})
        unsupported (intent/resolve-plan
                     plan candidates
                     {:advisory advice :exact-relationship-count 0})]
    (is (= :flow (:shape supported)))
    (is (= :model-plus-structure (:planning-authority supported)))
    (is (= 2 (:max-seeds supported)))
    (is (= :adaptive (:shape unsupported)))
    (is (= :shape-neutral-fallback (:planning-authority unsupported)))
    (is (= advice (:advisory unsupported)))))

(deftest low-margin-advice-remains-visible-but-does-not-resolve-shape
  (let [advice {:status :available :suggested-shape :flow :margin 0.01}
        resolved (intent/resolve-plan
                  (intent/analyze "trace it")
                  [{:id "a" :intent-qualified? true
                    :intent-score 2 :intent-reasons [:query-term-trace]}
                   {:id "b" :intent-qualified? true
                    :intent-score 1 :intent-reasons [:query-term-trace]}]
                  {:advisory advice :minimum-advisory-margin 0.02
                   :exact-relationship-count 1})]
    (is (= :adaptive (:shape resolved)))
    (is (false? (get-in resolved [:structural-support
                                  :advisory-confident?])))
    (is (= advice (:advisory resolved)))))

(deftest explicit-overrides-remain-authoritative
  (let [plan (intent/analyze "anything" {:seed-mode :single})
        resolved (intent/resolve-plan
                  plan []
                  {:advisory {:status :available :suggested-shape :flow}
                   :exact-relationship-count 4})]
    (is (= :lookup (:shape resolved)))
    (is (= :single (:seed-mode resolved)))
    (is (= :caller (:planning-authority resolved)))))

(deftest endpoint-reranking-uses-structural-evidence-without-changing-scores
  (let [query "what modules expose HTTP endpoints?"
        plan (intent/analyze query)
        candidates [{:id "model" :name "type->model"
                     :qualified-name "app.actions/type->model"
                     :file "src/app/actions.clj" :doc "Returns a model."
                     :score 0.031}
                    {:id "safe" :name "safe-url?"
                     :qualified-name "app.http/safe-url?"
                     :file "src/app/http.clj" :doc "Checks an HTTP URL."
                     :score 0.030}
                    {:id "routes" :name "routes"
                     :qualified-name "app.session.api/routes"
                     :file "src/app/session/api.clj" :doc "`/api/session` routes."
                     :score 0.012}]
        result (intent/rerank query candidates plan)]
    (is (= ["routes" "safe" "model"] (mapv :id (:results result))))
    (is (= 0.012 (:score (first (:results result)))))
    (is (true? (:intent-qualified? (first (:results result)))))
    (is (false? (:intent-qualified? (second (:results result)))))
    (is (= :applied (:status result)))
    (is (:reordered? result))))

(deftest multi-seed-selection-is-bounded-and-diverse
  (let [plan (assoc (intent/analyze "list API endpoints") :max-seeds 2)
        candidates [{:id "a" :file "src/a.clj" :qualified-name "app.a/routes"
                     :intent-score 4}
                    {:id "a-helper" :file "src/a.clj" :qualified-name "app.a/handler"
                     :intent-score 3}
                    {:id "b" :file "src/b.clj" :qualified-name "app.b/routes"
                     :intent-score 2}]]
    (is (= ["a" "b"] (mapv :id (intent/select-seeds candidates plan))))))

(deftest endpoint-root-selection-rejects-broad-semantic-matches
  (let [plan (intent/analyze "what modules expose HTTP endpoints?")
        candidates [{:id "server" :file "server.clj"
                     :qualified-name "server/respond"
                     :intent-score 9.0 :intent-qualified? false}
                    {:id "routes" :file "routes.clj"
                     :qualified-name "api/routes"
                     :intent-score 5.0 :intent-qualified? true}]]
    (is (= ["routes"] (mapv :id (intent/select-seeds candidates plan))))))
