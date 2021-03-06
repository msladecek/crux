(ns crux.ui.events
  (:require
   [ajax.edn :as ajax-edn]
   [cljs.reader :as reader]
   [clojure.string :as string]
   [crux.ui.common :as common]
   [crux.ui.http]
   [re-frame.core :as rf]
   [tick.alpha.api :as t]))

(rf/reg-fx
 :scroll-top
 common/scroll-top)

(rf/reg-event-fx
 ::inject-metadata
 (fn [{:keys [db]} [_ title handler]]
   (let [result-meta (some-> (js/document.querySelector
                              (str "meta[title=" title "]"))
                             (.getAttribute "content"))
         edn-content (reader/read-string result-meta)]
     (if edn-content
       {:db (assoc db handler edn-content)}
       (js/console.warn "Metadata not found")))))

(rf/reg-event-db
 ::navigate-to-root-view
 (fn [db [_ view]]
   (-> (assoc-in db [:form-pane :hidden?] false)
       (assoc-in [:form-pane :view] view))))

(rf/reg-event-db
 ::toggle-form-pane
 (fn [db [_ & bool]]
   (update-in db [:form-pane :hidden?] #(if (seq bool) (first bool) (not %)))))

(rf/reg-event-db
 ::toggle-form-history
 (fn [db [_ component & bool]]
   (update-in db [:form-pane :history component] #(if (seq bool) (first bool) (not %)))))

(rf/reg-event-db
 ::toggle-show-vt
 (fn [db [_ component bool]]
   (update-in db [:form-pane :show-vt? component] #(if (nil? %) (not bool) (not %)))))

(rf/reg-event-db
 ::toggle-show-tt
 (fn [db [_ component bool]]
   (update-in db [:form-pane :show-tt? component] #(if (nil? %) (not bool) (not %)))))

(rf/reg-event-db
 ::set-form-pane-view
 (fn [db [_ view]]
   (assoc-in db [:form-pane :view] view)))

(rf/reg-event-db
 ::query-table-error
 (fn [db [_ error]]
   (assoc-in db [:query :error] error)))

(rf/reg-event-db
 ::set-query-result-pane-loading
 (fn [db [_ bool]]
   (assoc-in db [:query :result-pane :loading?] bool)))

(rf/reg-event-fx
 ::inject-local-storage
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc :query-history (reader/read-string
                                   (.getItem js/window.localStorage "query"))))}))

(defn vec-remove
  [pos coll]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

(rf/reg-event-fx
 ::remove-query-from-local-storage
 (fn [{:keys [db]} [_ idx]]
   (let [query-history (:query-history db)
         updated-history (vec-remove idx query-history)]
     {:db (assoc db :query-history updated-history)
      :local-storage ["query" updated-history]})))

(rf/reg-fx
 :local-storage
 (fn [[k data]]
   (.setItem js/window.localStorage k data)))

(rf/reg-event-fx
 ::go-to-query-view
 (fn [{:keys [db]} [_ {:keys [values]}]]
   (let [{:strs [q vtd vtt ttd ttt]} values
         query-params (->>
                       (merge
                        (common/edn->query-params (reader/read-string q))
                        {:valid-time (common/date-time->datetime vtd vtt)
                         :transaction-time (common/date-time->datetime ttd ttt)})
                       (remove #(nil? (second %)))
                       (into {}))
         limit (:limit query-params 100)
         current-storage (or (reader/read-string (.getItem js/window.localStorage "query")) [])
         updated-history (conj (into [] (remove #(= query-params %) current-storage)) query-params)]
     {:db (assoc db :query-history updated-history)
      :dispatch [:navigate :query {} (assoc query-params :limit limit)]
      :local-storage ["query" updated-history]})))

(rf/reg-event-fx
 ::goto-previous-query-page
 (fn [{:keys [db]} _]
   (let [query-params (get-in db [:current-route :query-params])
         offset (js/parseInt (get-in db [:current-route :query-params :offset] 0))
         limit (js/parseInt (get-in db [:current-route :query-params :limit] 100))]
     {:db db
      :dispatch [:navigate :query {} (assoc query-params :offset (-> (- offset limit)
                                                                     (max 0)
                                                                     str))]})))

(rf/reg-event-fx
 ::goto-next-query-page
 (fn [{:keys [db]} _]
   (let [query-params (get-in db [:current-route :query-params])
         offset (js/parseInt (get-in db [:current-route :query-params :offset] 0))
         limit (js/parseInt (get-in db [:current-route :query-params :limit] 100))]
     {:db db
      :dispatch [:navigate :query {} (assoc query-params :offset (-> (+ offset limit) str))]})))

(rf/reg-event-fx
 ::set-entity-pane-document
 (fn [{:keys [db]} _]
   (let [query-params (-> (get-in db [:current-route :query-params])
                          (select-keys [:valid-time :transaction-time :eid]))]
     {:db (assoc-in db [:entity :right-pane :view] :document)
      :dispatch [:navigate :entity nil query-params]})))

(rf/reg-event-fx
 ::set-entity-pane-history
 (fn [{:keys [db]} _]
   (let [query-params (-> (get-in db [:current-route :query-params])
                          (assoc :history true)
                          (assoc :with-docs true)
                          (assoc :sort-order "desc"))]
     {:db (assoc-in db [:entity :right-pane :view] :history)
      :dispatch [:navigate :entity nil query-params]})))

(rf/reg-event-fx
 ::set-entity-pane-raw-edn
 (fn [{:keys [db]} _]
   (let [query-params (-> (get-in db [:current-route :query-params])
                          (select-keys [:valid-time :transaction-time :eid]))]
     {:db (assoc-in db [:entity :right-pane :view] :raw-edn)
      :dispatch [:navigate :entity nil query-params]})))

(rf/reg-event-db
 ::set-entity-result-pane-loading
 (fn [db [_ bool]]
   (assoc-in db [:entity :result-pane :loading?] bool)))

(rf/reg-event-fx
 ::go-to-entity-view
 (fn [{:keys [db]} [_ {{:strs [eid vtd vtt ttd ttt]} :values}]]
   (let [query-params (->>
                       {:valid-time (common/date-time->datetime vtd vtt)
                        :transaction-time (common/date-time->datetime ttd ttt)
                        :eid eid}
                       (remove #(nil? (second %)))
                       (into {}))]
     {:db db
      :dispatch [:navigate :entity nil query-params]})))

(rf/reg-event-db
 ::entity-result-pane-document-error
 (fn [db [_ error]]
   (assoc-in db [:entity :error] error)))

(rf/reg-event-db
 ::set-entity-result-pane-history-diffs?
 (fn [db [_ bool]]
   (assoc-in db [:entity :result-pane :diffs?] bool)))
