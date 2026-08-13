(ns officemach.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-2817`: this
  repo previously had NO demo page and no generator at all (`docs/`
  held only `index.html`, `operator-quickstart.md`,
  `business-model.md` and `adr/0001-architecture.md`).

  Every row on the generated page is REAL output of this repo's own
  actor stack -- `officemach.operation/build` (the langgraph-clj
  StateGraph) is driven with `langgraph.graph/run*`, the
  `officemach.advisor` proposal is censored by
  `officemach.governor/check`, the rollout gate is
  `officemach.phase/gate`, and everything that survives is committed
  through `officemach.store`. Nothing on the page is hand-typed
  domain data: batch ids, product types, dielectric-withstand-test
  readings, quantities, defect rates, equipment ids, maintenance and
  shipment draft numbers and every HARD-hold detail string come from
  `officemach.store/sample-data!` and the governor's own violation
  maps.

  Determinism: the scenario is a fixed sequence against a freshly
  seeded `MemStore`, the advisor is the deterministic `mock-advisor`,
  and no timestamp or random value reaches the page. Two consecutive
  runs are byte-identical -- verify by diffing.

  Build-time invariant (not a comment -- `-main` throws): the real
  governor must actually produce every HARD-hold rule this page
  claims to demonstrate. If a future change to the governor, the
  registry or the seed data stops a rule from firing, the build fails
  rather than shipping a console that advertises a guard nobody
  exercised.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [officemach.advisor :as advisor]
            [officemach.governor :as governor]
            [officemach.operation :as op]
            [officemach.phase :as phase]
            [officemach.store :as store]))

;; ----------------------------- scenario driver -----------------------------

(def ^:private coordinator
  "The plant coordinator identity every scenario runs as, at the
  default rollout phase (`officemach.phase/default-phase`)."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

(defn- at-phase [ph] (assoc coordinator :phase ph))

(defn- hallucinating-advisor
  "A deliberately COMPROMISED advisor: it takes the real proposal the
  deterministic `officemach.advisor/mock-advisor` would have made and
  swaps its `:effect` for `:assembly/actuate` -- an effect outside
  `officemach.governor/allowed-proposal-effects`, i.e. direct
  assembly/test-bench-equipment control.

  This exists so the page can show governor check 3
  (`:equipment-control-blocked`) firing ON ITS OWN. With the honest
  mock advisor that rule can only be reached together with
  `:unknown-op` (an unrecognized op yields `:effect :noop`), which
  would leave the actor's central scope boundary looking like a
  side-effect of a different guard. The advisor is the untrusted node
  by construction (see `officemach.advisor` ns docstring), so
  substituting a lying one is exactly the threat model the governor
  exists for -- and the hold below is a real governor verdict, not a
  hand-written row."
  [inner]
  (reify advisor/Advisor
    (-advise [_ st req]
      (assoc (advisor/-advise inner st req) :effect :assembly/actuate))))

(defn- run-scenario!
  "Runs ONE labelled scenario through the real actor and returns a map
  describing what actually happened.

  `:facts` is the exact slice of `officemach.store/ledger` this
  scenario appended (captured by ledger length before/after, so the
  join is real rather than reconstructed), and `:audit` is the graph's
  own `:audit` channel from the final run -- which is where
  `:approval-requested` / `:approval-granted` live (the operation
  graph deliberately keeps those in the run state and writes only
  commits and holds to the SSoT ledger).

  NOTE `langgraph.graph/run*` returns an ENVELOPE
  `{:state .. :events .. :status .. :frontier ..}`, not the channel map
  -- `officemach.sim` only ever prints it, so nothing in this repo
  previously had to unwrap it. Read `:state`."
  [{:keys [db actor counter]} label ctx request & [{:keys [approval]}]]
  (let [before (count (store/ledger db))
        tid (str "sc-" (swap! counter inc))
        r1 (g/run* actor {:request request :context ctx} {:thread-id tid})
        r2 (when approval
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        envelope (or r2 r1)
        st (:state envelope)]
    {:label label
     :thread tid
     :op (:op request)
     :subject (:subject request)
     :phase (:phase ctx)
     :approval approval
     :status (:status envelope)
     :interrupted-at (:status r1)
     :disposition (:disposition st)
     :confidence (get-in st [:proposal :confidence])
     :proposal-effect (get-in st [:proposal :effect])
     :facts (vec (drop before (store/ledger db)))
     :audit (vec (:audit st))}))

(defn run-demo!
  "Drives the REAL `officemach` actor through a scenario that reaches
  every disposition this actor can produce, against the repo's own
  `officemach.store/sample-data!` seed (batches `batch-001`
  calculator / `batch-002` photocopier / `batch-003` cash-register;
  equipment `assembly-001` assembly-station / `test-bench-002`
  dielectric-test-bench).

  Clean lane: a clean `:log-production-batch` patch auto-commits at
  phase 3 (the only op in any phase's `:auto` set); a maintenance
  window, a safety concern and a shipment all escalate to a human and
  are approved; one further shipment is REJECTED by the human, which
  is a hold with no SSoT mutation.

  HARD lane: every one of the twelve HARD rules in
  `officemach.governor` is fired directly and independently -- a
  mis-wired non-`:propose` request, an unrecognized op, a compromised
  advisor proposing a direct assembly actuation effect, an
  `:actuate-equipment? true` maintenance draft, a self-issued
  safety-certification mark, maintenance against the UNVERIFIED
  `test-bench-002`, a double-schedule of `mnt-1`, a shipment against
  the UNVERIFIED `batch-003`, a shipment that would push `batch-002`
  past its own logged production quantity, a shipment whose headroom
  cannot be recomputed at all, and three implausible production-batch
  readings (fabricated product type, out-of-band dielectric-withstand
  test, out-of-band defect rate).

  Rollout lane: the same clean shipment replayed under phase 1, where
  `:coordinate-shipment` is not yet a permitted write -- a hold with
  NO governor violation, which is a different thing from a compliance
  hold and is rendered as such.

  Returns {:db .. :scenarios [..]} -- every value the renderer reads."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        liar (op/build db {:advisor (hallucinating-advisor (advisor/mock-advisor))})
        ctx {:db db :actor actor :counter (atom 0)}
        liar-ctx (assoc ctx :actor liar)
        approve {:status :approved :by "coord-1"}
        reject {:status :rejected :by "coord-1"}
        scenarios
        [;; ---------------- clean lane ----------------
         (run-scenario! ctx "生産バッチ記録の更新（governor clean → phase 3 auto-commit）"
                        coordinator
                        {:op :log-production-batch :effect :propose :subject "batch-001"
                         :patch {:product-type :calculator
                                 :dielectric-withstand-test-kv 1.6
                                 :defect-rate-percent 0.7
                                 :last-assessed "2026-07-14"}})

         (run-scenario! ctx "組立ステーションの保守予定（設備は検証済・登録済 → 人間承認 → commit）"
                        coordinator
                        {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                         :value {:equipment-id "assembly-001" :maintenance-type :calibration
                                 :scheduled-date "2026-08-01" :actuate-equipment? false}}
                        {:approval approve})

         (run-scenario! ctx "安全懸念の報告（常に escalate、confidence によらない → 人間承認 → commit）"
                        coordinator
                        {:op :flag-safety-concern :effect :propose :subject "concern-1"
                         :value {:equipment-id "assembly-001" :severity :moderate
                                 :description "電源部の絶縁劣化疑い、筐体の接地確認要"}}
                        {:approval approve})

         (run-scenario! ctx "出荷調整（batch-001 は検証済・登録済、数量に余裕あり → 人間承認 → commit）"
                        coordinator
                        {:op :coordinate-shipment :effect :propose :subject "ship-1"
                         :value {:batch-id "batch-001" :units 50.0
                                 :destination "buyer-yard-north"}}
                        {:approval approve})

         (run-scenario! ctx "出荷調整を人間が却下（governor は clean、承認者が否認 → hold、SSoT 無変更）"
                        coordinator
                        {:op :coordinate-shipment :effect :propose :subject "ship-4"
                         :value {:batch-id "batch-001" :units 25.0
                                 :destination "buyer-yard-west"}}
                        {:approval reject})

         ;; ---------------- HARD lane ----------------
         (run-scenario! ctx "呼び出し側の request :effect が :propose でない（提案専用モードの迂回）"
                        coordinator
                        {:op :log-production-batch :effect :direct-write :subject "batch-001"
                         :patch {:product-type :calculator}})

         (run-scenario! ctx "許可リスト外の op（組立ラインの直接起動要求）"
                        coordinator
                        {:op :actuate-assembly-line :effect :propose :subject "batch-001"})

         (run-scenario! liar-ctx "advisor が :assembly/actuate 効果を提案（compromised advisor / 設備の直接操作）"
                        coordinator
                        {:op :schedule-maintenance :effect :propose :subject "mnt-9"
                         :value {:equipment-id "assembly-001" :maintenance-type :calibration
                                 :scheduled-date "2026-08-05" :actuate-equipment? false}})

         (run-scenario! ctx "保守提案が :actuate-equipment? true（設備の直接操作 / 恒久禁止）"
                        coordinator
                        {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                         :value {:equipment-id "assembly-001" :maintenance-type :force-run
                                 :scheduled-date "2026-09-01" :actuate-equipment? true}})

         (run-scenario! ctx "安全認証マーク（UL/CE 等）の自己発行（認証機関の専権事項 / 恒久禁止）"
                        coordinator
                        {:op :log-production-batch :effect :propose :subject "batch-001"
                         :patch {:issue-certification? true}})

         (run-scenario! ctx "未検証・未登録の絶縁耐圧試験ベンチに対する保守予定"
                        coordinator
                        {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                         :value {:equipment-id "test-bench-002" :maintenance-type :calibration
                                 :scheduled-date "2026-08-01" :actuate-equipment? false}})

         (run-scenario! ctx "同一保守記録の二重スケジュール（mnt-1 は既に commit 済み）"
                        coordinator
                        {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                         :value {:equipment-id "assembly-001" :maintenance-type :calibration
                                 :scheduled-date "2026-08-01" :actuate-equipment? false}})

         (run-scenario! ctx "未検証・未登録バッチ（batch-003）に対する出荷調整"
                        coordinator
                        {:op :coordinate-shipment :effect :propose :subject "ship-2"
                         :value {:batch-id "batch-003" :units 100.0
                                 :destination "buyer-yard-south"}})

         (run-scenario! ctx "出荷数量が batch-002 自身の記録済み生産数量を超過（独立に再計算）"
                        coordinator
                        {:op :coordinate-shipment :effect :propose :subject "ship-3"
                         :value {:batch-id "batch-002" :units 100.0
                                 :destination "buyer-yard-east"}})

         (run-scenario! ctx "出荷申請に数量が無く空き容量を検算できない（検算不能は余裕ではない）"
                        coordinator
                        {:op :coordinate-shipment :effect :propose :subject "ship-5"
                         :value {:batch-id "batch-001" :destination "buyer-yard-north"}})

         (run-scenario! ctx "生産バッチ記録に既知集合外の product-type"
                        coordinator
                        {:op :log-production-batch :effect :propose :subject "batch-001"
                         :patch {:product-type :unobtainium}})

         (run-scenario! ctx "生産バッチ記録に物理的に不可能な絶縁耐圧試験値"
                        coordinator
                        {:op :log-production-batch :effect :propose :subject "batch-001"
                         :patch {:dielectric-withstand-test-kv 999999.0}})

         (run-scenario! ctx "生産バッチ記録に物理的に不可能な不良率"
                        coordinator
                        {:op :log-production-batch :effect :propose :subject "batch-001"
                         :patch {:defect-rate-percent 999.0}})

         ;; ---------------- rollout lane ----------------
         (run-scenario! ctx "phase 1 では :coordinate-shipment がまだ書き込み許可されていない"
                        (at-phase 1)
                        {:op :coordinate-shipment :effect :propose :subject "ship-6"
                         :value {:batch-id "batch-001" :units 10.0
                                 :destination "buyer-yard-north"}})]]
    {:db db :scenarios (vec scenarios)}))

;; ----------------------------- derived views -----------------------------

(defn- hold-facts
  "Every `:governor-hold` ledger fact that actually carries a governor
  violation. A phase-gate hold is also written as `:governor-hold` but
  with an empty `:violations` -- counting those as compliance holds
  would inflate the number this page reports."
  [db]
  (filterv #(and (= :governor-hold (:t %)) (seq (:violations %))) (store/ledger db)))

(defn- phase-gate-facts [db]
  (filterv #(and (= :governor-hold (:t %)) (empty? (:violations %))) (store/ledger db)))

(defn- observed-hard-rules [db]
  (into (sorted-set) (mapcat (fn [f] (map :rule (:violations f))) (hold-facts db))))

(def expected-hard-rules
  "Every HARD rule `officemach.governor/check` can raise. `run-demo!`
  is written to fire all of them; `-main` throws unless the real run
  actually did. This is the build-time invariant referred to in the ns
  docstring -- a guard that stops firing must break the build, not
  quietly disappear from the console."
  #{:not-propose-effect
    :unknown-op
    :equipment-control-blocked
    :equipment-actuate-blocked
    :certification-authority-blocked
    :equipment-not-verified
    :already-scheduled
    :batch-not-verified
    :shipment-quantity-exceeded
    :invalid-product-type
    :invalid-dielectric-withstand-test-kv
    :invalid-defect-rate})

(defn- approval-audit
  "All approval-lifecycle facts from every scenario's own graph state,
  in scenario order. The operation graph keeps these in the run's
  `:audit` channel (only commits and holds reach the SSoT ledger), so
  this is the only place the approver's identity is observable at
  all."
  [scenarios]
  (vec (for [s scenarios
             f (:audit s)
             :when (#{:approval-requested :approval-granted :approval-rejected} (:t f))]
         (assoc f :scenario (:label s) :thread (:thread s)))))

(defn- stored-entity
  "Look the committed SSoT entity for one approved subject back up
  through the store's own accessors -- the same read a downstream
  auditor would perform."
  [db op subject]
  (case op
    :log-production-batch (store/batch db subject)
    :schedule-maintenance (store/maintenance db subject)
    :coordinate-shipment (store/shipment db subject)
    :flag-safety-concern (first (filter #(= subject (:id %)) (store/safety-concerns db)))
    nil))

(defn- approver-retention
  "MEASURED, not asserted: for every approval that was actually
  granted and then committed, read the resulting SSoT entity back out
  of the store and check whether the approver's identity survived the
  write.

  `officemach.operation/commit-record` builds BOTH a `:value` and a
  `:payload`, and the approval node adds `:approved-by` to the
  `:payload` only, while `officemach.store/commit-record!`
  destructures `:value`. Whether that means the approver is dropped is
  a fact about the code as it stands today, so the page derives it
  here instead of hard-coding a claim that would become a lie the
  moment the store is fixed."
  [db scenarios]
  (let [granted (filterv #(= :approval-granted (:t %)) (approval-audit scenarios))
        rows (for [{:keys [op subject by scenario]} granted
                   :let [e (stored-entity db op subject)]]
               {:op op :subject subject :by by :scenario scenario
                :entity-found? (some? e)
                :retained (:approved-by e)})]
    {:rows (vec rows)
     :granted (count granted)
     :retained (count (filter :retained rows))}))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- yn [b] (if b "<span class=\"ok\">yes</span>" "<span class=\"err\">no</span>"))

(defn- dec-fmt
  "Fixed-decimal rendering pinned to `Locale/ROOT` -- `clojure.core/format`
  follows the default locale, which would make the page's byte content
  depend on the machine that built it."
  [places v]
  (if (number? v)
    (String/format java.util.Locale/ROOT (str "%." places "f") (object-array [(double v)]))
    "&mdash;"))

(defn- n1 [v] (dec-fmt 1 v))

(defn- conf
  "Confidence needs two decimals: the advisor's clean-path confidences
  are 0.95 / 0.9 / 0.3, and rounding 0.95 to one place prints `1.0` --
  a number the advisor never produced, on the column a reader uses to
  judge the escalation threshold (`officemach.governor/confidence-floor`
  is 0.6)."
  [v]
  (dec-fmt 2 v))

(defn- tr [& cells] (str "        <tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- section [title lead headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (apply str (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (rows body-rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

;; --- batches ---

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- outcome-cell [f]
  (cond
    (nil? f) "<span class=\"muted\">活動なし</span>"
    (= :committed (:t f)) "<span class=\"ok\">commit</span>"
    (= :approval-rejected (:t f)) "<span class=\"warn\">承認者が否認 &middot; hold</span>"
    (and (= :governor-hold (:t f)) (seq (:violations f)))
    (str "<span class=\"critical\">HARD hold &middot; "
         (esc (str/join ", " (map (comp name :rule) (:violations f)))) "</span>")
    (= :governor-hold (:t f))
    (str "<span class=\"warn\">phase gate hold &middot; "
         (esc (kw (:phase-reason f))) "</span>")
    :else "<span class=\"muted\">進行中</span>"))

(defn- batch-row [ledger {:keys [id product-type model dielectric-withstand-test-kv
                                 quantity-units defect-rate-percent verified? registered?
                                 shipped-units last-assessed]}]
  (tr (str "<code>" (esc id) "</code>")
      (esc (kw product-type))
      (esc model)
      (n1 dielectric-withstand-test-kv)
      (n1 quantity-units)
      (n1 defect-rate-percent)
      (n1 shipped-units)
      (yn verified?)
      (yn registered?)
      (esc last-assessed)
      (outcome-cell (last-fact-for ledger id))))

(defn- equipment-row [{:keys [id kind verified? registered? last-maintenance-date
                              last-scheduled-maintenance-date]}]
  (tr (str "<code>" (esc id) "</code>")
      (esc (kw kind))
      (yn verified?)
      (yn registered?)
      (if last-maintenance-date (esc last-maintenance-date) "<span class=\"muted\">記録なし</span>")
      (if last-scheduled-maintenance-date
        (str "<span class=\"ok\">" (esc last-scheduled-maintenance-date) "</span>")
        "<span class=\"muted\">未予定</span>")))

;; --- action gate (derived from officemach.phase / officemach.governor) ---

(defn- gate-row [ph-3 op]
  (let [writes? (contains? (:writes ph-3) op)
        auto? (contains? (:auto ph-3) op)]
    (tr (str "<code>:" (esc (kw op)) "</code>")
        (if writes? "<span class=\"ok\">許可</span>" "<span class=\"err\">不可</span>")
        (if auto?
          "<span class=\"ok\">governor clean なら auto-commit</span>"
          "<span class=\"warn\">常に人間承認（どの phase でも auto 集合に入らない）</span>")
        (if (= :flag-safety-concern op)
          (str "<span class=\"warn\">high-stakes: <code>:"
               (esc (kw (first governor/high-stakes))) "</code> &middot; confidence によらず escalate</span>")
          (str "confidence &lt; " governor/confidence-floor " で escalate")))))

;; --- scenarios ---

(defn- scenario-rules [s]
  (let [vs (mapcat :violations (filter #(= :governor-hold (:t %)) (:facts s)))]
    (if (seq vs)
      (str/join ", " (map (comp esc name :rule) vs))
      "&mdash;")))

(defn- scenario-outcome [s]
  (let [f (last (:facts s))]
    (cond
      (and (= :hold (:disposition s)) (nil? f)) "<span class=\"warn\">hold</span>"
      :else (outcome-cell f))))

(defn- scenario-row [s]
  (tr (esc (:label s))
      (str "<code>:" (esc (kw (:op s))) "</code>")
      (str "<code>" (esc (:subject s)) "</code>")
      (str "phase " (esc (:phase s)))
      (if-let [a (:approval s)]
        (str "<span class=\"" (if (= :approved (:status a)) "ok" "warn") "\">"
             (esc (kw (:status a))) " by " (esc (:by a)) "</span>")
        "<span class=\"muted\">人間に届かず</span>")
      (conf (:confidence s))
      (scenario-outcome s)
      (scenario-rules s)))

;; --- hard holds ---

(defn- hold-rule-rows [db]
  (rows (for [f (hold-facts db)
              v (:violations f)]
          (tr (str "<code>:" (esc (name (:rule v))) "</code>")
              (str "<code>:" (esc (kw (:op f))) "</code>")
              (str "<code>" (esc (:subject f)) "</code>")
              (conf (:confidence f))
              (esc (:detail v))))))

;; --- approvals ---

(defn- approval-row [{:keys [t op subject by reason confidence scenario]}]
  (tr (case t
        :approval-requested "<span class=\"warn\">approval-requested</span>"
        :approval-granted "<span class=\"ok\">approval-granted</span>"
        :approval-rejected "<span class=\"err\">approval-rejected</span>"
        (esc (kw t)))
      (str "<code>:" (esc (kw op)) "</code>")
      (str "<code>" (esc subject) "</code>")
      (if by (str "<code>" (esc by) "</code>") "<span class=\"muted\">&mdash;</span>")
      (if reason (str "<code>:" (esc (kw reason)) "</code>") "&mdash;")
      (if (number? confidence) (conf confidence) "&mdash;")
      (esc scenario)))

(defn- approver-retention-rows [{:keys [rows]}]
  (for [{:keys [op subject by entity-found? retained]} rows]
    (tr (str "<code>:" (esc (kw op)) "</code>")
        (str "<code>" (esc subject) "</code>")
        (str "<code>" (esc by) "</code>")
        (if entity-found? "<span class=\"ok\">あり</span>" "<span class=\"err\">なし</span>")
        (if retained
          (str "<span class=\"ok\"><code>" (esc retained) "</code></span>")
          "<span class=\"err\">保持されず（監査ログのみ）</span>"))))

(defn- retention-banner
  "The disclosure sentence is DERIVED from the measurement above, so it
  changes by itself if `officemach.store/commit-record!` is ever
  taught to read the record's `:payload`."
  [{:keys [granted retained]}]
  (cond
    (zero? granted)
    "<p class=\"banner warn\">この実行では承認が 1 件も発生していない。</p>"

    (= granted retained)
    (str "<p class=\"banner ok\">承認者の識別子は commit 後の SSoT 実体から読み戻せる（"
         retained " / " granted " 件）。監査ログと SSoT のどちらからでも「誰が承認したか」に答えられる。</p>")

    (zero? retained)
    (str "<p class=\"banner err\">承認者の識別子は commit 後の SSoT 実体から読み戻せない（0 / "
         granted " 件）。<code>officemach.operation/commit-record</code> は <code>:value</code> と "
         "<code>:payload</code> の両方を作り、承認ノードは <code>:payload</code> にだけ "
         "<code>:approved-by</code> を足すが、<code>officemach.store/commit-record!</code> は "
         "<code>:value</code> だけを読む。したがって上表の承認者は <strong>監査ログ由来であって、"
         "記録には残っていない</strong> —— 「誰も承認していない」ではなく「store が落とした」。"
         "この文はレンダリング時に実測から導出しており、store が直れば自動で書き換わる。</p>")

    :else
    (str "<p class=\"banner warn\">承認者の識別子が SSoT 実体から読み戻せたのは " retained
         " / " granted " 件のみ。残りは監査ログ由来であって、記録には残っていない。</p>")))

;; --- drafts / concerns / ledger ---

(defn- draft-row [r]
  (tr (str "<code>" (esc (get r "record_id")) "</code>")
      (esc (get r "kind"))
      (esc (or (get r "maintenance_id") (get r "shipment_id")))
      (if-let [e (get r "equipment_id")] (str "<code>" (esc e) "</code>") "&mdash;")
      (if (get r "immutable") "<span class=\"ok\">immutable</span>" "&mdash;")))

(defn- maintenance-row [{:keys [id equipment-id maintenance-type scheduled-date
                                maintenance-number scheduled?]}]
  (tr (str "<code>" (esc id) "</code>")
      (str "<code>" (esc equipment-id) "</code>")
      (esc (kw maintenance-type))
      (esc scheduled-date)
      (str "<code>" (esc maintenance-number) "</code>")
      (if scheduled? "<span class=\"ok\">scheduled</span>" "<span class=\"muted\">draft</span>")))

(defn- concern-row [{:keys [id equipment-id severity description]}]
  (tr (str "<code>" (esc id) "</code>")
      (str "<code>" (esc equipment-id) "</code>")
      (str "<span class=\"warn\">" (esc (kw severity)) "</span>")
      (esc description)))

(defn- ledger-row [i {:keys [t op subject disposition basis phase-reason phase]}]
  (tr (str i)
      (case t
        :committed "<span class=\"ok\">committed</span>"
        :governor-hold "<span class=\"critical\">governor-hold</span>"
        :approval-rejected "<span class=\"err\">approval-rejected</span>"
        (esc (kw t)))
      (str "<code>:" (esc (kw op)) "</code>")
      (str "<code>" (esc subject) "</code>")
      (esc (kw disposition))
      (if (seq basis)
        (esc (str/join ", " (map kw basis)))
        "&mdash;")
      (if phase-reason
        (str "<code>:" (esc (kw phase-reason)) "</code> (phase " (esc phase) ")")
        "&mdash;")))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator-console document from a completed
  `run-demo!` result. Reads nothing but real store state and real
  graph output."
  [{:keys [db scenarios]}]
  (let [ledger (vec (store/ledger db))
        holds (hold-facts db)
        gate-holds (phase-gate-facts db)
        commits (filterv #(= :committed (:t %)) ledger)
        observed (observed-hard-rules db)
        ph3 (get phase/phases 3)
        retention (approver-retention db scenarios)
        approvals (approval-audit scenarios)]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-2817 &middot; 事務用機械器具製造 オペレータコンソール</title>\n"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>事務用機械器具製造（コンピュータ・周辺機器を除く / ISIC 2817） — オペレータコンソール</h1>\n"
     "  <span class=\"badge\">read-only サンプル &middot; governor-gated &middot; 保守予定・安全懸念・出荷調整は常に人間承認</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>この実行の要約</h2>\n"
     "    <p class=\"muted\">このページは <code>clojure -M:dev:render-html</code> がビルド時に生成する。"
     "<code>officemach.operation/build</code>（langgraph-clj StateGraph）を実際に "
     "<code>langgraph.graph/run*</code> で駆動し、<code>officemach.advisor</code> の提案を "
     "<code>officemach.governor/check</code> が検閲し、<code>officemach.phase/gate</code> が"
     "ロールアウト段階で絞り、生き残ったものだけを <code>officemach.store</code> に commit した"
     "結果を出している。以下の数値・識別子・違反理由はすべてその実行の出力であって、"
     "手書きの文字列ではない。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>指標</th><th>値</th></tr></thead>\n"
     "      <tbody>\n"
     (rows [(tr "シナリオ数" (count scenarios))
            (tr "監査台帳の事実数" (count ledger))
            (tr "commit" (str "<span class=\"ok\">" (count commits) "</span>"))
            (tr "HARD hold（governor 違反あり）" (str "<span class=\"critical\">" (count holds) "</span>"))
            (tr "発火した HARD ルール種別" (str "<span class=\"critical\">" (count observed) "</span> / "
                                       (count expected-hard-rules)))
            (tr "phase gate による hold（governor 違反なし）" (str "<span class=\"warn\">" (count gate-holds) "</span>"))
            (tr "人間承認の要求" (count (filter #(= :approval-requested (:t %)) approvals)))
            (tr "承認" (count (filter #(= :approval-granted (:t %)) approvals)))
            (tr "否認" (count (filter #(= :approval-rejected (:t %)) approvals)))
            (tr "既定 phase" (str "phase " phase/default-phase " (" (esc (:label ph3)) ")"))]) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     (section "生産バッチ（SSoT）"
              (str "<code>officemach.store/all-batches</code> の現在値。"
                   "<code>verified?</code> / <code>registered?</code> は "
                   "<code>officemach.registry/batch-ready?</code> が独立に読む地の事実であって、"
                   "advisor の自己申告ではない。<code>出荷済</code> は commit された出荷調整で実際に増えた値。")
              ["バッチ" "product-type" "型式" "耐圧試験 kV" "生産数量" "不良率 %" "出荷済" "verified?" "registered?" "最終評価日" "最後の op"]
              (map (partial batch-row ledger) (store/all-batches db)))

     (section "組立・試験設備（SSoT）"
              (str "<code>officemach.store/all-equipment</code> の現在値。"
                   "<code>officemach.registry/equipment-ready?</code> が両方 true でなければ、"
                   "その設備に対する保守予定は governor が HARD hold する。")
              ["設備" "種別" "verified?" "registered?" "最終保守日" "予定された保守日"]
              (map equipment-row (store/all-equipment db)))

     (section "アクションゲート（Office Machinery Plant Operations Governor &times; phase 3）"
              (str "この表は <code>officemach.phase/phases</code> と <code>officemach.governor</code> の"
                   "実際の var から導出している（手書きの説明ではない）。"
                   "<code>:schedule-maintenance</code> / <code>:flag-safety-concern</code> / "
                   "<code>:coordinate-shipment</code> はどの phase の <code>:auto</code> 集合にも入らない —— "
                   "phase 3 でも人間承認が要る恒久的な構造であって、まだ来ていないマイルストーンではない。")
              ["op" "phase 3 で書き込み可か" "auto-commit" "エスカレーション条件"]
              (map (partial gate-row ph3) (sort-by name governor/allowed-ops)))

     (section "シナリオ（実行順）"
              (str "1 行 = 1 回のアクター実行。<code>承認</code>列が「人間に届かず」の行は、"
                   "HARD hold なので承認者の目に触れることすらない —— "
                   "HARD 違反は override できないという設計がここに現れている。")
              ["シナリオ" "op" "対象" "phase" "承認" "confidence" "結果" "発火したルール"]
              (map scenario-row scenarios))

     "  <section class=\"card\">\n"
     "    <h2>HARD hold の内訳（この実行で実際に発火したもの）</h2>\n"
     "    <p class=\"muted\">HARD 違反は override できない。数量・検証状態はすべて governor が"
     "バッチ／設備自身の永続フィールドから独立に再計算しており、提案の自己申告を信用しない。"
     "<code>officemach.governor</code> が持つ " (count expected-hard-rules) " 種の HARD ルールのうち、"
     "この実行で <strong>" (count observed) " 種</strong>が実際に発火した"
     "（ビルドはこれが揃わなければ失敗する）。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>ルール</th><th>op</th><th>対象</th><th>confidence</th><th>governor が書いた理由</th></tr></thead>\n"
     "      <tbody>\n"
     (hold-rule-rows db) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     (section "承認ワークフロー（グラフの :audit チャネル）"
              (str "<code>interrupt-before #{:request-approval}</code> がアクターを止めて人間に渡した記録。"
                   "operation グラフはこれらを実行状態に保持し、SSoT 台帳には commit と hold だけを書くので、"
                   "承認者の識別子が観測できるのはここだけである。")
              ["事実" "op" "対象" "承認者" "理由" "confidence" "シナリオ"]
              (map approval-row approvals))

     "  <section class=\"card\">\n"
     "    <h2>承認者の識別子は記録に残るか（実測）</h2>\n"
     "    <p class=\"muted\">承認された各 commit について、store の公開アクセサ"
     "（<code>batch</code> / <code>maintenance</code> / <code>shipment</code> / "
     "<code>safety-concerns</code>）で SSoT 実体を読み戻し、<code>:approved-by</code> が"
     "実在するかをレンダリング時に確かめている。結論を焼き付けていないので、"
     "store の挙動が変われば下の表と文もひとりでに変わる。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>op</th><th>対象</th><th>監査ログ上の承認者</th><th>SSoT 実体</th><th>実体から読める :approved-by</th></tr></thead>\n"
     "      <tbody>\n"
     (rows (approver-retention-rows retention)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    " (retention-banner retention) "\n"
     "  </section>\n"

     (section "保守予定（commit 済み）"
              (str "<code>officemach.store/all-maintenance</code>。"
                   "<code>maintenance-number</code> は <code>officemach.registry/register-maintenance</code> が"
                   "採番した実値であって、ページのために作った番号ではない。")
              ["保守記録" "設備" "種別" "予定日" "採番" "状態"]
              (map maintenance-row (store/all-maintenance db)))

     (section "登録済みドラフト（append-only）"
              (str "<code>officemach.store/maintenance-history</code> と "
                   "<code>officemach.store/shipment-history</code>。"
                   "いずれも「予定した／調整した」という記録のドラフトであって、"
                   "設備の起動でも実際の運送手配でもない。")
              ["採番" "種別" "対象 id" "設備" "immutable"]
              (map draft-row (concat (store/maintenance-history db)
                                     (store/shipment-history db))))

     (section "安全懸念ログ（append-only）"
              (str "<code>officemach.store/safety-concerns</code>。"
                   "<code>:flag-safety-concern</code> は <code>officemach.governor/high-stakes</code> に"
                   "属するため confidence によらず必ず人間に上がる。"
                   "設備が未検証であっても安全報告自体は塞がない（事務手続き上の不備を理由に"
                   "安全報告を止めない）。")
              ["懸念" "設備" "深刻度" "内容"]
              (map concern-row (store/safety-concerns db)))

     "  <section class=\"card\">\n"
     "    <h2>監査台帳（この実行の全件）</h2>\n"
     "    <p class=\"muted\">追記のみの決定事実ログ。commit も hold も同じ台帳に並ぶ —— "
     "「何が起きなかったか」も監査対象だからである。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>事実</th><th>op</th><th>対象</th><th>disposition</th><th>根拠</th><th>phase gate</th></tr></thead>\n"
     "      <tbody>\n"
     (rows (map-indexed (fn [i f] (ledger-row (inc i) f)) ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-2817 &middot; ISIC 2817 事務用機械器具製造（コンピュータ及び周辺機器を除く）。"
     "このアクターは組立・試験設備を直接操作せず、安全認証マーク（UL/CE 等）を自己発行しない —— "
     "いずれも恒久的・無条件の HARD ブロックである。ページは "
     "<code>src/officemach/render_html.clj</code> がビルド時に生成した。</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db scenarios] :as result} (run-demo!)
        holds (hold-facts db)
        observed (observed-hard-rules db)
        missing (into (sorted-set) (remove observed expected-hard-rules))]
    ;; Build-time invariants -- a console that advertises guards which
    ;; never fired is worse than no console.
    (when (zero? (count holds))
      (throw (ex-info "governor produced zero HARD holds -- refusing to write a console that claims guards nobody exercised"
                      {:ledger-size (count (store/ledger db))})))
    (when (seq missing)
      (throw (ex-info "governor did not exercise every HARD rule this console claims"
                      {:missing missing :observed observed})))
    (when (zero? (count (filter #(= :approval-granted (:t %)) (approval-audit scenarios))))
      (throw (ex-info "no approval was ever granted -- the human-in-the-loop lane did not run"
                      {:scenarios (count scenarios)})))
    (let [dir (.getParentFile (java.io.File. ^String out))]
      (when dir (.mkdirs dir)))
    (spit out (render result))
    (println "wrote" out
             (str "(" (count scenarios) " scenarios, "
                  (count (store/ledger db)) " ledger facts, "
                  (count holds) " HARD holds over "
                  (count observed) "/" (count expected-hard-rules) " rules, "
                  (count (store/maintenance-history db)) " maintenance drafts, "
                  (count (store/shipment-history db)) " shipment drafts, "
                  (count (store/safety-concerns db)) " safety concerns)"))))
