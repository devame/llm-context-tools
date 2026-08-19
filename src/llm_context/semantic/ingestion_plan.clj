(ns llm-context.semantic.ingestion-plan
  "Pure selection of bounded semantic ingestion request profiles.")

(def profiles #{:steady :cold :auto})

(defn- upsert-dominated?
  [{:keys [upserts deletes]}]
  (let [upserts (long (or upserts 0))
        deletes (long (or deletes 0))
        total (+ upserts deletes)]
    (and (pos? upserts)
         (or (zero? total)
             (>= (/ (double upserts) total) 0.8)))))

(defn- bounded-concurrency [requested request-limit max-inflight]
  (max 1 (min (long requested)
              (long (quot max-inflight request-limit)))))

(defn plan
  "Return deterministic lease, request, concurrency, and in-flight bounds.

  The input intentionally contains only observed counts and explicit
  configuration. The planner performs no I/O and does not infer hardware
  capacity from a GPU model name. `:previous-profile` supplies the one bit of
  state needed for auto-profile hysteresis."
  [{:keys [pending-symbol-jobs pending-provider-documents-estimate
           operation-mix accelerator provider-version
           configured-request-batch configured-request-concurrency
           configured-max-inflight-documents profile previous-profile
           cold-ingestion]}]
  (let [profile (or profile :steady)
        _ (when-not (contains? profiles profile)
            (throw (ex-info "Unknown semantic ingestion profile"
                            {:profile profile :supported profiles})))
        pending-symbols (max 0 (long (or pending-symbol-jobs 0)))
        pending-documents
        (max pending-symbols
             (long (or pending-provider-documents-estimate pending-symbols)))
        steady-request (long configured-request-batch)
        steady-concurrency (long configured-request-concurrency)
        steady-inflight
        (long (or configured-max-inflight-documents
                  (* steady-request steady-concurrency)))
        {:keys [enabled backlog-threshold exit-threshold update-batch-size
                update-concurrency max-inflight-provider-documents]}
        cold-ingestion
        cold-request (long (or update-batch-size steady-request))
        cold-concurrency (long (or update-concurrency steady-concurrency))
        cold-inflight
        (long (or max-inflight-provider-documents
                  (* cold-request cold-concurrency)))
        threshold (long (or backlog-threshold Long/MAX_VALUE))
        exit-threshold (long (or exit-threshold threshold))
        provider-supported? (= "1.7.0" provider-version)
        workload-supported? (upsert-dominated? operation-mix)
        continuing-cold?
        (and (= :cold previous-profile)
             (> pending-documents exit-threshold))
        [selected reason]
        (case profile
          :steady [:steady :configured-steady]
          :cold
          (cond
            (not enabled) [:steady :cold-disabled]
            (not provider-supported?) [:steady :provider-version-unqualified]
            (not workload-supported?) [:steady :delete-heavy-workload]
            (< pending-documents threshold) [:steady :cold-backlog-too-small]
            :else [:cold :configured-cold])
          :auto
          (cond
            (not enabled) [:steady :cold-disabled]
            (not provider-supported?) [:steady :provider-version-unqualified]
            (not workload-supported?) [:steady :delete-heavy-workload]
            continuing-cold? [:cold :auto-cold-hysteresis]
            (>= pending-documents threshold) [:cold :auto-cold-backlog]
            :else [:steady :auto-steady-backlog]))
        request-limit (if (= :cold selected) cold-request steady-request)
        configured-concurrency
        (if (= :cold selected) cold-concurrency steady-concurrency)
        max-inflight (if (= :cold selected) cold-inflight steady-inflight)
        concurrency-limit
        (bounded-concurrency configured-concurrency request-limit max-inflight)
        lease-limit
        ;; One symbol normally renders one provider document. The worker also
        ;; enforces request and in-flight document bounds after rendering, so a
        ;; multi-chunk symbol cannot increase concurrent provider work.
        (max 1 (min max-inflight
                    (* request-limit concurrency-limit)))]
    {:profile selected
     :lease-symbol-limit lease-limit
     :request-provider-document-limit request-limit
     :request-concurrency-limit concurrency-limit
     :max-inflight-provider-documents max-inflight
     :accelerator accelerator
     :pending-symbol-jobs pending-symbols
     :pending-provider-documents-estimate pending-documents
     :reason reason}))
