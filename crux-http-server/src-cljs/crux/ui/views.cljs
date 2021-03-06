(ns crux.ui.views
  (:require
   [clojure.string :as string]
   [cljs.pprint :as pprint]
   [crux.ui.events :as events]
   [crux.ui.codemirror :as cm]
   [crux.ui.common :as common]
   [crux.ui.subscriptions :as sub]
   [crux.ui.uikit.table :as table]
   [fork.core :as fork]
   [reagent.core :as r]
   [re-frame.core :as rf]))

(defn vt-tt-icons
  [component]
  (let [show-vt? @(rf/subscribe [::sub/show-vt? component])
        show-tt? @(rf/subscribe [::sub/show-tt? component])]
    [:<>
     [:i.fas.fa-history.vt-tt-icons
      {:on-click #(rf/dispatch [::events/toggle-show-vt component show-vt?])
       :title "Valid Time"}
      [:span.vt-tt-icon-text
       {:class (when show-vt? "vt-tt-icon-text--active")}
       "Valid Time"]]
     [:i.fas.fa-clock.vt-tt-icons
      {:on-click #(rf/dispatch [::events/toggle-show-tt component show-tt?])
       :title "Transaction Time"}
      [:span.vt-tt-icon-text
       {:class (when show-tt? "vt-tt-icon-text--active")}
       "Transaction Time"]]]))

(defn vt-tt-inputs
  [{:keys [values touched errors handle-change handle-blur]} component]
  (let [show-vt? @(rf/subscribe [::sub/show-vt? component])
        show-tt? @(rf/subscribe [::sub/show-tt? component])]
    [:div.crux-time
     [:div.input-group
      {:class (when-not show-vt? "hidden")}
      [:div.input-group-label.label
       [:label "Valid Time"]]
      [:input.input.input-time
       {:type "date"
        :name "vtd"
        :value (get values "vtd")
        :on-change handle-change
        :on-blur handle-blur}]
      [:input.input.input-time
       {:type "time"
        :name "vtt"
        :value (get values "vtt")
        :on-change handle-change
        :on-blur handle-blur}]
      (when (and (or (get touched "vtd")
                     (get touched "vtt"))
                 (get errors "vt"))
        [:p.input-error (get errors "vt")])]
     [:div.input-group
      {:class (when-not show-tt? "hidden")}
      [:div.input-group-label.label
       [:label "Transaction Time" ]]
      [:input.input.input-time
       {:type "date"
        :name "ttd"
        :value (get values "ttd")
        :on-change handle-change
        :on-blur handle-blur}]
      [:input.input.input-time
       {:type "time"
        :name "ttt"
        :value (get values "ttt")
        :on-change handle-change
        :on-blur handle-blur}]
      (when (and (or (get touched "ttd")
                     (get touched "ttt"))
                 (get errors "tt"))
        [:p.input-error (get errors "tt")])]]))

(defn query-validation
  [values]
  (let [invalid? #(empty? (string/trim (or (get values %) "")))
        validation {"q" (when (invalid? "q") "Query box is empty")
                    "vt" (when (apply not= ((juxt #(% "vtd")
                                                  #(% "vtt")) invalid?))
                           "Fill out both inputs or none")
                    "tt" (when (apply not= ((juxt #(% "ttd")
                                                  #(% "ttt")) invalid?))
                           "Fill out both inputs or none")}]
    (when (some some? (vals validation)) validation)))

(defn- submit-form-on-keypress [evt form-id]
  (when (and (.-ctrlKey evt) (= 13 (.-keyCode evt)))
    (let [form-button (js/document.querySelector (str form-id " button"))]
      (.click form-button))))

(defn query-form
  []
  ;; we need to create a cm instance holder to modify the CodeMirror code
  (let [cm-instance (atom nil)
        form-id "#form-query"]
    (r/create-class {:component-did-mount (fn []
                                            (-> (js/document.querySelector form-id)
                                                (.addEventListener "keydown" #(submit-form-on-keypress % form-id) true)))
                     :component-will-unmount (fn []
                                               (-> (js/document.querySelector form-id)
                                                   (.removeEventListener "keydown" #(submit-form-on-keypress % form-id) true)))
                     :reagent-render
                     (fn []
                       [fork/form {:form-id (subs form-id 1)
                                   :validation query-validation
                                   :prevent-default? true
                                   :clean-on-unmount? true
                                   :initial-values @(rf/subscribe [::sub/initial-values-query])
                                   :on-submit #(do
                                                 (rf/dispatch [::events/toggle-form-history :query false])
                                                 (rf/dispatch [::events/go-to-query-view %]))}
                        (fn [{:keys [values
                                     errors
                                     touched
                                     set-values
                                     set-touched
                                     form-id
                                     handle-submit] :as props}]
                          (let [loading? @(rf/subscribe [::sub/query-result-pane-loading?])
                                form-pane-history-q @(rf/subscribe [::sub/form-pane-history :query])
                                query-history-list @(rf/subscribe [::sub/query-form-history])
                                disabled? (or loading? (some some? (vals errors)))]
                            [:<>
                             [:form
                              {:id form-id
                               :on-submit handle-submit}
                              [:div.input-textarea
                               [cm/code-mirror (get values "q")
                                {:cm-instance cm-instance
                                 :class "cm-textarea__query"
                                 :on-change #(set-values {"q" %})
                                 :on-blur #(set-touched "q")}]]
                              [:div.query-form-options
                               [vt-tt-icons :query]
                               [:div.expand-collapse__group.form-pane__history
                                {:on-click #(rf/dispatch [::events/toggle-form-history :query])}
                                [:span.expand-collapse__txt
                                 [:span.form-pane__arrow
                                  [common/arrow-svg form-pane-history-q] "Query History"]]]]
                              [:div
                               {:class (if form-pane-history-q "expand" "collapse")}
                               [:div.form-pane__history-scrollable
                                (map-indexed
                                 (fn [idx {:strs [q vtd vtt ttd ttt]}]
                                   ^{:key (gensym)}
                                   [:div.form-pane__history-scrollable-el
                                    [:div.form-pane__history-delete
                                     {:on-click #(rf/dispatch [::events/remove-query-from-local-storage idx])}
                                     [:i.fas.fa-trash-alt]]
                                    [:div.form-pane__history-scrollable-el-left
                                     {:on-click #(do
                                                   (rf/dispatch [::events/toggle-form-history :query])
                                                   (.setValue @cm-instance q)
                                                   (set-values
                                                    {"q" q
                                                     "vtd" vtd
                                                     "vtt" vtt
                                                     "ttd" ttd
                                                     "ttt" ttt}))}
                                     [:div
                                      {:style {:margin-bottom "1rem"}}
                                      [:span.form-pane__history-headings "Valid Time: "]
                                      [:span.form-pane__history-txt (str vtd " " vtt)]]
                                     (when (and ttd ttt)
                                       [:div
                                        [:span.form-pane__history-headings "Transaction Time: "]
                                        [:span.form-pane__history-txt (str ttd " " ttt)]])
                                     [:div {:style {:margin-top "1rem"}}
                                      [cm/code-mirror-static q {:class "cm-textarea__query"}]]]])
                                 query-history-list)]]
                              (when (and (get touched "q")
                                         (get errors "q"))
                                [:p.input-error (get errors "q")])
                              [vt-tt-inputs props :query]
                              [:div.button-line
                               [:button.button
                                {:type "submit"
                                 :class (when-not disabled? "form__button")
                                 :disabled disabled?}
                                "Submit Query"]
                               [:p.button-hint "(Ctrl + Enter)"]]]]))])})))

(defn entity-validation
  [values]
  (let [invalid? #(empty? (string/trim (or (get values %) "")))
        validation {"eid" (when (invalid? "eid") "Entity id is empty")
                    "vt" (when (apply not= ((juxt #(% "vtd")
                                                   #(% "vtt")) invalid?))
                           "Fill out both inputs or none")
                    "tt" (when (apply not= ((juxt #(% "ttd")
                                                  #(% "ttt")) invalid?))
                           "Fill out both inputs or none")}]
    (when (some some? (vals validation)) validation)))

(defn entity-form
  []
  (let [form-id "#form-entity"]
    (r/create-class
     {:component-did-mount (fn []
                             (-> (js/document.querySelector form-id)
                                 (.addEventListener "keydown" #(submit-form-on-keypress % form-id) true)))
      :component-will-unmount (fn []
                                (-> (js/document.querySelector form-id)
                                    (.removeEventListener "keydown" #(submit-form-on-keypress % form-id) true)))
      :reagent-render
      (fn []
        [fork/form {:form-id (subs form-id 1)
                    :prevent-default? true
                    :clean-on-unmount? true
                    :validation entity-validation
                    :initial-values @(rf/subscribe [::sub/initial-values-entity])
                    :on-submit #(rf/dispatch [::events/go-to-entity-view %])}
         (fn [{:keys [values
                      touched
                      errors
                      form-id
                      set-values
                      set-touched
                      handle-submit] :as props}]
           (let [loading? @(rf/subscribe [::sub/entity-result-pane-loading?])
                 disabled? (or loading? (some some? (vals errors)))]
             [:form
              {:id form-id
               :on-submit handle-submit}
              [:div.input-textarea
               [cm/code-mirror (get values "eid")
                {:class "cm-textarea__entity"
                 :on-change #(set-values {"eid" %})
                 :on-blur #(set-touched "eid")}]
               (when (and (get touched "eid")
                          (get errors "eid"))
                 [:p.input-error (get errors "eid")])]
              [:div.query-form-options
               [vt-tt-icons :entity]]
              [vt-tt-inputs props :entity]
              [:div.button-line
               [:button.button
                {:type "submit"
                 :class (when-not disabled? "form__button")
                 :disabled disabled?}
                "Submit Entity"]
               [:p.button-hint "(Ctrl + Enter)"]]]))])})))

(defn query-table
  []
  (let [{:keys [error data]} @(rf/subscribe [::sub/query-data-table])
        loading? @(rf/subscribe [::sub/query-result-pane-loading?])]
    [:<>
     (cond
       error [:div.error-box error]
       (and
        (:rows data)
        (empty? (:rows data))) [:div.no-results "No results found!"]
       :else [:<>
              [table/table data]
              [:div.query-table-downloads
               "Download as:"
               [:a.query-table-downloads__link
                {:href @(rf/subscribe [::sub/query-data-download-link "csv"])}
                "CSV"]
               [:a.query-table-downloads__link
                {:href @(rf/subscribe [::sub/query-data-download-link "tsv"])}
                "TSV"]]])]))

(defn vt-tt-entity-box
  [vt tt]
  [:div.entity-vt-tt
   [:div.entity-vt-tt__title
    "Valid Time"]
   [:div.entity-vt-tt__value (str vt)]
   [:div.entity-vt-tt__title
    "Transaction Time"]
   [:div.entity-vt-tt__value (str tt)]])

(defn entity-document
  []
  (let [{:keys [eid vt tt document linked-entities error]}
        @(rf/subscribe [::sub/entity-result-pane-document])
        loading? @(rf/subscribe [::sub/entity-result-pane-loading?])]
    [:div.entity-map__container
     (if loading?
       [:div.entity-map.entity-map--loading
        [:i.fas.fa-spinner.entity-map__load-icon]]
       (if error
         [:div.error-box error]
         [:<>
          [::div.entity-map
           [cm/code-snippet document linked-entities]]
          [vt-tt-entity-box vt tt]]))]))

(defn- entity-history-document []
  (let [diffs-tab? @(rf/subscribe [::sub/entity-result-pane-history-diffs?])
        entity-error @(rf/subscribe [::sub/entity-result-pane-document-error])
        loading? @(rf/subscribe [::sub/entity-result-pane-loading?])
        {:keys [query-params path-params]} @(rf/subscribe [::sub/current-route])
        asc-order? (= "asc" (:sort-order query-params))]
    [:<>
     [:div.history-diffs__options
      [:div.select.history-sorting-group
       [:select
        {:name "diffs-order"
         :value (:sort-order query-params)
         :on-change #(rf/dispatch [:navigate :entity path-params
                                   (assoc query-params :sort-order (if asc-order? "desc" "asc"))])}
        [:option {:value "asc"} "Ascending"]
        [:option {:value "desc"} "Descending"]]]
      [:div.history-checkbox__group
       [:div.history-diffs__checkbox
        [:input
         {:checked diffs-tab?
          :on-change #(rf/dispatch [::events/set-entity-result-pane-history-diffs?
                                    (if diffs-tab? false true)])
          :type "checkbox"}]]
       [:span "Diffs"]]]
     [:div.entity-histories__container
      (if loading?
        [:div.entity-map.entity-map--loading
         [:i.fas.fa-spinner.entity-map__load-icon]]
        (cond
          entity-error [:div.error-box entity-error]
          (not diffs-tab?) (let [{:keys [entity-history]} @(rf/subscribe [::sub/entity-result-pane-history])]
                             [:div.entity-histories
                              (for [{:keys [crux.tx/tx-time crux.db/valid-time crux.db/doc]
                                     :as history-elem} entity-history]
                                ^{:key history-elem}
                                [:div.entity-history__container
                                 [:div.entity-map
                                  [cm/code-snippet doc {}]]
                                 [vt-tt-entity-box valid-time tx-time]])])
          diffs-tab? (let [{:keys [up-to-date-doc history-diffs]} @(rf/subscribe [::sub/entity-result-pane-history-diffs])]
                       [:div.entity-histories
                        [:div.entity-history__container
                         [:div.entity-map
                          [cm/code-snippet (:crux.db/doc up-to-date-doc) {}]]
                         [vt-tt-entity-box
                          (:crux.db/valid-time up-to-date-doc)
                          (:crux.tx/tx-time up-to-date-doc)]]
                        (for [{:keys [additions deletions
                                      crux.tx/tx-time crux.db/valid-time]
                               :as history-elem} history-diffs]
                          ^{:key history-elem}
                          [:div.entity-history__container
                           [:div.entity-map__diffs-group
                            [:div.entity-map
                             (when additions
                               [:<>
                                [:span {:style {:color "green"}}
                                 "+ Additions:"]
                                [cm/code-snippet additions {}]])
                             (when deletions
                               [:<>
                                [:span {:style {:color "red"}}
                                 "- Deletions:"]
                                [cm/code-snippet deletions {}]])]]
                           [vt-tt-entity-box valid-time tx-time]])])
          :else nil))]]))

(defn entity-raw-edn
  []
  (let [{:keys [document error]}
        @(rf/subscribe [::sub/entity-result-pane-document])
        loading? @(rf/subscribe [::sub/entity-result-pane-loading?])]
    [:div.entity-raw-edn__container
     (if loading?
       [:div.entity-map.entity-map--loading
        [:i.fas.fa-spinner.entity-map__load-icon]]
       (if error
         [:div.error-box error]
         [:div.entity-raw-edn
          (with-out-str (pprint/pprint document))]))]))


(defn entity-pane []
  (let [pane-view @(rf/subscribe [::sub/entity-pane-view])]
    (if (= pane-view :entity-root)
      [:div.entity-root
       [:div.entity-root__title
        "Getting Started"]
       [:p "To look for a particular entity:"]
       [:ul
        [:li "Enter the name of the entity to search for in the search box (for example, " [:i ":bar"] ")"]
        [:li "Select a " [:b "valid time"] " and a " [:b "transaction time"] " (if needed)"]
        [:li "Click " [:b "submit entity"] " to go to the entity's page"]]]
      [:<>

       [:div.pane-nav
        [:div.pane-nav__tab
         {:class (when (= pane-view :document) "pane-nav__tab--active")
          :on-click #(rf/dispatch [::events/set-entity-pane-document])}
         "Document"]
        [:div.pane-nav__tab
         {:class (when (= pane-view :history) "pane-nav__tab--active")
          :on-click #(rf/dispatch [::events/set-entity-pane-history])}
         "History"]
        [:div.pane-nav__tab
         {:class (if (= pane-view :raw-edn)
                   "pane-nav__tab--active"
                   "pane-nav__tab--hover")
          :on-click #(rf/dispatch [::events/set-entity-pane-raw-edn])}
         "Raw EDN"]]
       [:section.document-section
        (case pane-view
          :document [entity-document]
          :history [entity-history-document]
          :raw-edn [entity-raw-edn]
          nil)]])))

(defn query-pane
  []
  (let [pane-view @(rf/subscribe [::sub/query-pane-view])]
    (if (= pane-view :query-root)
      [:div.query-root
       [:div.query-root__title
        "Getting Started"]
       [:p "To perform a query:"]
       [:ul
        [:li "Enter the desired query into the query editor."]
        [:li "Select a " [:b "valid time"] " and a " [:b "transaction time"] " (if needed)"]
        [:li "Click " [:b "submit query"] " to perform the query and get the results in a table."]]]
      [query-table])))

(defn form-pane
  []
  (let [form-pane-hidden? @(rf/subscribe [::sub/form-pane-hidden?])
        form-pane-view @(rf/subscribe [::sub/form-pane-view])]
    [:div.form-pane
     [:div.expand-collapse__group
      {:on-click #(rf/dispatch [::events/toggle-form-pane])}
      (if form-pane-hidden?
        [:i.fas.fa-expand]
        [:i.fas.fa-compress])
      [:span.expand-collapse__txt
       {:style {:margin-left ".5rem"}}
       "Console"]]

     [:div.form-pane__content
      {:class (if form-pane-hidden? "collapse" "expand")}
      [:div.form-pane__tabs
       [:a.form-pane__tab
        {:class (when (= :query form-pane-view)
                  "form-pane__tab--selected")
         :on-click #(rf/dispatch [::events/set-form-pane-view :query])}
        "Query"]
       [:a.form-pane__tab
        {:class (when (= :entity form-pane-view)
                  "form-pane__tab--selected")
         :on-click #(rf/dispatch [::events/set-form-pane-view :entity])}
        "Entity"]]
      [:div
       {:class (if (= :query form-pane-view) "visible" "hidden")}
       [query-form]]
      [:div
       {:class (if (= :entity form-pane-view) "visible" "hidden")}
       [entity-form]]]]))

(defn root-page
  []
  [:div.root-contents
   [:p "Welcome to the Crux Console! Get started below:"]
   [:p [:a {:href (common/route->url :query)
            :on-click #(rf/dispatch [::events/navigate-to-root-view :query])}
        "Performing a query"]]
   [:p [:a
        {:href (common/route->url :entity)
         :on-click #(rf/dispatch [::events/navigate-to-root-view :entity])}
        "Searching for an entity"]]])

(defn navbar
  []
  [:nav.nav
   [:a {:href (common/route->url :homepage)}
    [:img.crux-logo {:src "/crux-horizontal-bw.svg" }]]])

(defn view []
  (let [{{:keys [name]} :data} @(rf/subscribe [::sub/current-route])]
    [:<>
     #_[:pre (with-out-str (pprint/pprint (dissoc @(rf/subscribe [:db]) :query)))]
     [:div.container.page-pane
      [navbar]
      (if (= name :homepage)
        [root-page]
        [:<>
         (when name [form-pane])
         [:div.result-pane
          (case name
            :query [query-pane]
            :entity [entity-pane]
            [:div "no matching"])]])]]))
