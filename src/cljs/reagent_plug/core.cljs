(ns reagent-plug.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as reagent :refer [atom]]
            [cljs.core.async :as a]
            [cljs.core.match :refer-macros [match]]
            [cljs.pprint :as pprint]
            [ajax.core :as ajx])
  (:import goog.History))

(defprotocol IPlug
  (injected-arg [this])
  (setup! [this])
  (cleanup! [this]))

(defrecord Plug [injected setup! cleanup!]
  IPlug
  (injected-arg [this] (:injected this))
  (setup! [this] ((:setup! this)))
  (cleanup! [this] ((:cleanup! this))))

(defmulti make-plug ^IPlug (fn [spec arg] (first spec)))

(defmethod make-plug ::a/tap [[_ & [chan-factory]] mult]
  (let [c (if chan-factory (chan-factory) (a/chan))]
    (->Plug c #(a/tap mult c false) #(a/untap mult c))))

(def ^:private gen-watch-key
  (let [next-int (atom 0)]
    (fn [] (keyword "reagent-plug.core" (str "watch-key-" (swap! next-int inc))))))

(defmethod make-plug ::a/atom-watch [[_ & [chan-factory]] atm]
  (let [c ((or chan-factory a/chan))
        key (gen-watch-key)]
    (->Plug c
            #(add-watch atm key (fn [& args] (a/put! c args)))
            #(remove-watch atm key))
    ))

(defmethod make-plug ::a/local-chan [[_ & [chan-factory]] _]
  (let [c ((or chan-factory a/chan))]
    (->Plug c #(do nil) #(a/close! c))))

(defmethod make-plug ::reagent/cursor [[_ path] ratom]
  (let [curs (reagent/cursor ratom path)]
    (->Plug curs #(do nil) #(reset! curs nil))))

(defn pluggable [specs cpn]
  (fn [& args]
    (let [plugs (map (fn [arg spec] (if (nil? spec) nil (make-plug spec arg))) specs args)]
      (.log js/console "Created plugs")
      (reagent/create-class
        {:display-name "pluggable-wrapper"
         :component-will-mount
         (fn [this]
           (->> plugs (remove nil?) (map setup!) doall))
         :component-will-unmount
         (fn [this]
           (->> plugs (remove nil?) (map cleanup!) doall))
         :reagent-render
         (fn [& args]
           (let [new-args (->> (concat plugs (repeat nil))
                               (map (fn [arg plug]
                                      (if (nil? plug) arg (injected-arg plug))) args)
                               )]
             (into [cpn] new-args)))
         }))))


;; -------------------------
;; Example

(enable-console-print!)

(defn find-translation [target-language sentence]
  (let [c (a/chan)]
    (.log js/console "calling...")
    (ajx/GET "http://api.mymemory.translated.net/get"
             {:response-format :json :format :json :keywords? true
              :params {:q sentence
                       :langpair (str "en" "|" target-language)}
              :handler #(a/put! c %)
              :error-handler #(a/close! c)})
    c))

(go (.log js/console (a/<! (find-translation "fr" "Good Morning"))))

#_(defn call-api "Calls BandSquare API with the specified"
  [method path body & [opts]]
  (let [resp-chan (a/chan 1)]
    ((get fn-of-method method) (str api-prefix path)
      (merge {:response-format :json :format :json :keywords? true
              :params body
              :handler #(a/put! resp-chan {:outcome :success :data %})
              :error-handler #(a/put! resp-chan {:outcome :error :data %})
              :with-credentials true
              } opts))
    resp-chan))



(declare
  <global-input>
  <stateful-container>
  <stateful-1>
  <stateful-2>
  <stateful-3> <stateful-3-container>
  <output-log>)


(defn home-page []
  [:div.container
   [:h2 "Welcome to reagent-plug"]
   ])

;; -------------------------
;; Initialize app
(defn mount-root []
  (reagent/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (mount-root))
