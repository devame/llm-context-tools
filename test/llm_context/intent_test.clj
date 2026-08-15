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
        candidates [{:id "a" :structurally-qualified? true
                     :structural-reasons [:call-source]}
                    {:id "b" :structurally-qualified? true
                     :structural-reasons [:call-target]}]
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
                  [{:id "a" :structurally-qualified? true
                    :intent-score 2 :structural-reasons [:call-source]}
                   {:id "b" :structurally-qualified? true
                    :intent-score 1 :structural-reasons [:call-target]}]
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
    (is (= :caller (:planning-authority resolved)))
    (is (= :no-evidence (:evidence-status resolved)))
    (is (= :rank-fallback (:seed-selection-authority resolved)))))

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
    (is (true? (:structurally-qualified? (first (:results result)))))
    (is (= [:route-like-identifier]
           (:structural-reasons (first (:results result)))))
    (is (false? (:intent-qualified? (second (:results result)))))
    (is (seq (:relevance-reasons (second (:results result)))))
    (is (= :applied (:status result)))
    (is (:reordered? result))))

(deftest multi-seed-selection-is-bounded-and-diverse
  (let [plan (assoc (intent/analyze "list API endpoints") :max-seeds 2)
        candidates [{:id "a" :file "src/a.clj" :qualified-name "app.a/routes"
                     :intent-score 4 :structurally-qualified? true}
                    {:id "a-helper" :file "src/a.clj" :qualified-name "app.a/handler"
                     :intent-score 3 :structurally-qualified? true}
                    {:id "b" :file "src/b.clj" :qualified-name "app.b/routes"
                     :intent-score 2 :structurally-qualified? true}]]
    (is (= ["a" "b"] (mapv :id (intent/select-seeds candidates plan))))))

(deftest endpoint-root-selection-rejects-broad-semantic-matches
  (let [plan (intent/analyze "what modules expose HTTP endpoints?")
        candidates [{:id "server" :file "server.clj"
                     :qualified-name "server/respond"
                     :intent-score 9.0 :structurally-qualified? false}
                    {:id "routes" :file "routes.clj"
                     :qualified-name "api/routes"
                     :intent-score 5.0 :structurally-qualified? true}]]
    (is (= ["routes"] (mapv :id (intent/select-seeds candidates plan))))))

(deftest relevance-does-not-masquerade-as-structural-qualification
  (let [query "what are the supported databases"
        plan (intent/analyze query)
        candidates
        [{:id "impersonation" :name "set-role-if-supported!"
          :qualified-name
          "metabase-enterprise.impersonation.driver/set-role-if-supported!"
          :file "enterprise/backend/src/impersonation/driver.clj"
          :signature "[driver conn database]"}
         {:id "versions" :name "supported-db-versions"
          :qualified-name "metabase.app-db.setup/supported-db-versions"
          :file "src/metabase/app_db/setup.clj"}
         {:id "search" :name "supported-db?"
          :qualified-name "metabase.search.appdb.core/supported-db?"
          :file "src/metabase/search/appdb/core.clj"}]
        results (:results (intent/rerank query candidates plan))
        advice (fn [shape]
                 {:provider :mixedbread-32m :status :available
                  :suggested-shape shape :margin 0.2})
        resolve (fn [shape relationships]
                  (intent/resolve-plan
                   plan results
                   {:advisory (advice shape)
                    :minimum-advisory-margin 0.02
                    :exact-relationship-count relationships}))]
    (is (every? :relevance-qualified? results))
    (is (every? (complement :structurally-qualified?) results))
    (is (every? empty? (map :structural-reasons results)))
    (doseq [[shape relationships] [[:lookup 0] [:set 0] [:flow 1]]]
      (let [resolved (resolve shape relationships)]
        (is (= :adaptive (:shape resolved)))
        (is (= :advisory-not-structurally-supported (:reason resolved)))
        (is (= :relevance-only (:evidence-status resolved)))
        (is (= :relevance-fallback (:seed-selection-authority resolved)))))))

(deftest automatic-shapes-consume-only-structural-qualification
  (let [plan (intent/analyze "show the relevant definitions")
        candidate (fn [id structural?]
                    {:id id :intent-score 2.0
                     :relevance-qualified? true
                     :structurally-qualified? structural?})
        advice (fn [shape]
                 {:status :available :suggested-shape shape :margin 0.2})]
    (is (= :lookup
           (:shape
            (intent/resolve-plan
             plan [(candidate "a" true) (candidate "b" false)]
             {:advisory (advice :lookup) :exact-relationship-count 0
              :minimum-advisory-margin 0.02}))))
    (is (= :set
           (:shape
            (intent/resolve-plan
             plan [(candidate "a" true) (candidate "b" true)]
             {:advisory (advice :set) :exact-relationship-count 0
              :minimum-advisory-margin 0.02}))))))
