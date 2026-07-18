(ns millwork.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout iteration 8): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`millwork.operation` -> `millwork.governor` -> `millwork.store`)
  through a scenario adapted from this repo's own `millwork.sim` demo
  driver (`clojure -M:dev:run`, confirmed to run correctly against the
  real seeded batch/equipment directory before this file was written --
  unlike `cloud-itonami-isic-851`'s `schoolops.sim`, this repo's own sim
  driver uses ids that DO match `millwork.store/sample-batches` /
  `millwork.store/sample-equipment`, and every disposition it produces
  matches `millwork.governor`'s own documented rules exactly, so it was
  safe to reuse rather than author from scratch), trimmed to a
  representative subset (one clean phase-3 auto-commit, one
  always-escalate maintenance schedule, one always-escalate
  safety-concern flag, one always-escalate shipment coordination, and
  three distinct HARD-hold reasons) and rendered deterministically -- no
  invented numbers, no timestamps in the page content, byte-identical
  across reruns against the same seed (verified by diffing two
  consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [millwork.store :as store]
            [millwork.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: batch-001 clears a clean production-batch intake
  patch (phase-3 auto-commit, no physical/financial risk -- a valid
  dimensional spec and a plausible last-assessed date, nothing to
  reject); a maintenance window (mnt-1) scheduled against equip-001
  (verified+registered CNC router) ALWAYS escalates --
  `:schedule-maintenance` is permanently absent from every phase's
  `:auto` set -- approved by a human plant supervisor; a safety concern
  (concern-1) flagged against equip-001 ALWAYS escalates (governor
  `high-stakes` `:coordination/safety-concern`) -- approved; a shipment
  (ship-1) coordinating 50 units of batch-001 (within its own logged
  500-unit production count, 100 already shipped) ALWAYS escalates
  (shipment coordination is also never auto-eligible) -- approved. Then
  three distinct HARD-hold reasons that never reach a human: a
  maintenance window (mnt-2) against equip-002 (UNVERIFIED/unregistered
  tenoning machine) HARD-holds on `:equipment-not-verified`; a shipment
  (ship-2) against batch-003 (UNVERIFIED/unregistered staircase batch)
  HARD-holds on `:batch-not-verified`; a shipment (ship-3) claiming 10
  more units of batch-002 (80 logged units, 75 already shipped -- 10
  more would exceed the batch's own recorded quantity by 5) HARD-holds
  on `:shipment-unit-count-exceeded`. Returns the resulting store --
  every field read by `render` below is real governor/store output, not
  a hand-typed copy."
  []
  (let [db (store/mem-store)
        _ (store/sample-data! db)
        actor (op/build db)]

    (exec! actor "batch1-intake" {:op :log-production-batch :effect :propose :subject "batch-001"
                                   :patch {:dimensional-spec :door-single-leaf :last-assessed "2026-07-18"}})

    (exec! actor "mnt1-schedule" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                                   :value {:equipment-id "equip-001" :maintenance-type :blade-and-bit-service
                                           :scheduled-date "2026-08-01" :finalize? false}})
    (approve! actor "mnt1-schedule")

    (exec! actor "concern1-flag" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                                   :value {:equipment-id "equip-001" :severity :moderate
                                           :description "router周辺の木粉濃度上昇 (dust hazard)"}})
    (approve! actor "concern1-flag")

    (exec! actor "ship1-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                                      :value {:batch-id "batch-001" :unit-count 50
                                              :destination "buyer-site-north"}})
    (approve! actor "ship1-coordinate")

    (exec! actor "mnt2-schedule" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                                   :value {:equipment-id "equip-002" :maintenance-type :tenon-chisel-calibration
                                           :scheduled-date "2026-08-01" :finalize? false}})

    (exec! actor "ship2-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                                      :value {:batch-id "batch-003" :unit-count 10
                                              :destination "buyer-site-south"}})

    (exec! actor "ship3-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                                      :value {:batch-id "batch-002" :unit-count 10
                                              :destination "buyer-site-east"}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- batch-row [ledger {:keys [id dimensional-spec product-type unit-count
                                  output-quality-percent verified? registered? shipped-unit-count]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s / %s units</td><td>%s%%</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (name (or dimensional-spec :n-a))) (esc (name (or product-type :n-a)))
          (esc shipped-unit-count) (esc unit-count) (esc output-quality-percent)
          (if (and verified? registered?) "<span class=\"ok\">verified &amp; registered</span>"
              "<span class=\"warn\">unverified / unregistered</span>")
          (status-cell ledger id)))

(defn- equipment-row [{:keys [id kind verified? registered? last-maintenance-date
                               last-scheduled-maintenance-date]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (name (or kind :n-a)))
          (if (and verified? registered?) "<span class=\"ok\">verified &amp; registered</span>"
              "<span class=\"warn\">unverified / unregistered</span>")
          (esc (or last-maintenance-date "never"))
          (esc (or last-scheduled-maintenance-date "none scheduled"))))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README `What
  ;; this actor does`, `millwork.governor`/`millwork.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no physical/financial risk</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; equipment verified/registered ground-truth checked, cutting-line finalize permanently blocked</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; high-stakes, never auto regardless of phase</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval &middot; batch verified/registered ground-truth checked, shipment unit count independently recomputed</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        batch-rows (str/join "\n" (map (partial batch-row ledger) batches))
        equipment-rows (str/join "\n" (map equipment-row equipment))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-1622 &middot; builders' carpentry and joinery (millwork)</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Builders' carpentry and joinery (millwork) plant operations (ISIC 1622) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · maintenance/shipment coordination always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>millwork.store</code> via <code>millwork.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Dimensional spec</th><th>Product type</th><th>Shipped / Quantity</th><th>Output quality</th><th>QC status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     batch-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Plant equipment</h2>\n"
     "    <p class=\"muted\">Panel saw / CNC router / tenoning machine / edge bander / finishing-line units. Maintenance may only ever be scheduled (drafted), never actuated by this actor.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Equipment</th><th>Kind</th><th>Commission status</th><th>Last maintenance date</th><th>Last scheduled maintenance date</th></tr></thead>\n"
     "      <tbody>\n"
     equipment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Millwork Shop Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Equipment/batch verified &amp; registered status and shipment unit counts are independently recomputed, never trusted from the proposal; cutting/joinery/finishing-line-equipment actuation and cutting-line-run finalization are permanently blocked.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts )")))
