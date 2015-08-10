(ns reagent-plug.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as reagent :refer [atom]]
            [cljs.core.async :as a]
            [cljs.core.match :refer-macros [match]]
            [cljs.pprint :as pprint])
  (:import goog.History))

;; -------------------------
;; Views


(def =global-input= (a/chan))
(def global-input< (a/mult =global-input=))

(def =global-output= (a/chan))

(def output-log (reagent/atom []))
(go-loop
  [i 0]
  (when-let [m (a/<! =global-output=)]
    (swap! output-log conj {:i i :m m})
    (recur (inc i))))

(declare
  <global-input>
  <stateful-container>
    <stateful-1>
    <stateful-2>
    <stateful-3> <stateful-3-container>
  <output-log>)

(defn <global-input> []
  (let [text (reagent/atom "")]
    (fn []
      [:div
       [:h2 "Global Input"]
       [:form.form-inline
        [:div.form-group [:input.form-control {:type "text" :value @text :on-change #(reset! text (-> % .-target .-value))}]]
        [:button {:type "submit" :class "btn btn-primary" :on-click #(do (a/put! =global-input= [:text @text])
                                                                         (reset! text ""))} "Send text"]
        [:button {:class "btn btn-default" :on-click #(a/put! =global-input= [:button1])} "Button 1"]
        [:button {:class "btn btn-default" :on-click #(a/put! =global-input= [:button2])} "Button 2"]]]
      )))

(defn <stateful-container> [child]
  (let [state (reagent/atom true)]
    (fn []
      [:div.panel
       [:div.panel-heading "Stateful Container"]
       [:div.panel-body
        [:div.row
         [:div.col-sm-3
          [:input {:type "checkbox" :checked @state :on-change #(do (swap! state not) :dummy)}]
          "Display child"]
         [:div.col-sm-9 (when @state [child])]]]])))

(defn <stateful-1> []
  (let [=input= (let [c (a/chan 1 (map (fn [m] {:through :stateful-1 :received m})))]
                  (a/tap global-input< c) c)]
    (a/pipe =input= =global-output= false)
    (fn []
      [:div
       [:h4 "Stateful component"]
       [:p "I pipe input to output before I get mounted"]])))

(defn <stateful-2> []
  (let [=input= (a/chan 1 (map (fn [m] {:through :stateful-2 :received m})))]
    (reagent/create-class
      {:display-name "<stateful-2>"
       :component-will-mount (fn [& args]
                               #_(a/put! =global-output= ["<stateful-2> will mount: " (reagent/argv (first args)) args])
                               (a/tap global-input< =input=)
                               (a/pipe =input= =global-output= false))
       :component-will-unmount (fn [& args]
                                 #_(a/put! =global-output= ["<stateful-2> will unmount: " (reagent/argv (first args)) args])
                                 (a/untap global-input< =input=)
                                 (a/close! =input=))
       :reagent-render (fn []
                         [:div
                          [:h4 "Stateful component 2"]
                          [:p "I pipe to the output but use lefecycle methods"]])})))

(defn pluggable [specs cpn]
  (fn [& ags]
    (let [plugs (->> specs
                     (map (fn [spec]
                            (match [spec]
                                   [nil] nil
                                   [[:tap]] (a/chan 1)
                                   [[:pipe]] (a/chan 1)
                                   :else nil)
                            ))
                     vec)]
      (.log js/console "Created plugs")
      (reagent/create-class
        {:display-name "pluggable-wrapper"
         :component-will-mount
         (fn [this]
           (let [args (drop 1 (reagent/argv this))]
             (->> specs (map (fn [arg plug spec]
                               (match [spec arg plug]
                                      [nil _ _] nil
                                      [[:tap] m c] (a/tap m c)
                                      [[:pipe] to c] (a/pipe c to false)
                                      :else nil)
                               ) args plugs) doall)
             ))
         :component-will-unmount
         (fn [this]
           (let [args (drop 1 (reagent/argv this))]
             (->> specs (map (fn [arg plug spec]
                               (match [spec arg plug]
                                      [nil _ _] nil
                                      [[:tap] m c] (do (a/untap m c) (a/close! c) #_(.log js/console "Untapped" m c))
                                      [[:pipe] to c] (do (a/close! c) #_(.log js/console "Closed pipe" to c))
                                      :else nil)
                               ) args plugs) doall)
             ))
         :reagent-render
         (fn [& args]
           (let [new-args (->> (concat plugs (repeat nil))
                               (map (fn [arg plug]
                                      (match [plug arg]
                                             [nil a] a
                                             [p _] p)) args)
                               )]
             (into [cpn] new-args)))
         }))))

(defn <stateful-3>* [=input= =global-output=]
  (let [c (a/chan 1 (map (fn [m] {:through :stateful-3 :received m})))]
    (a/pipe =input= c false)
    (a/pipe c =global-output= false)
    (.log js/console "Done init")
    (fn []
      [:div
       [:h4 "Stateful component 3"]
       [:p "I get injected stuff"]])))
(def <stateful-3> (pluggable [[:tap] [:pipe]] <stateful-3>*))

(defn <stateful-3-container> []
  [<stateful-3> global-input< =global-output=])

(defn <output-log> []
  [:div {:style {:max-height "400px" :overflow-y "scroll"}}
   [:h2 "Output log"]
   [:div
    (for [{:keys [i m]} (->> @output-log reverse)]
      ^{:key i} [:div.row
                 [:div.col-xs-2 [:strong (str "#" i " : ")]]
                 [:div.col-xs-10 [:pre (with-out-str (pprint/pprint m))]]
                 ])]])

(go (a/>! =global-output= {:message "salut"}))

(defn home-page []
  [:div.container
   [:h2 "Welcome to reagent-plug"]
   [:div.row
    [:div.col-md-6
     [<global-input>]
     [<stateful-container> <stateful-1>]
     [<stateful-container> <stateful-2>]
     [<stateful-container> <stateful-3-container>]]
    [:div.col-md-6
     [<output-log>]]]])

;; -------------------------
;; Initialize app
(defn mount-root []
  (reagent/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (mount-root))
