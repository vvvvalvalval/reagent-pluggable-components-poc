(ns reagent-plug.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [reagent-plug.util :refer [spy]]
                   [reagent-plug.core.macros :refer [plugged-cpnt defplugged]])
  (:require [reagent.core :as r :refer [atom]]
            [cljs.core.async :as a]
            [cljs.core.match :refer-macros [match]]
            [cljs.pprint :as pprint]
            [ajax.core :as ajx]
            )
  (:import goog.History))

;; ------------------------------------------------------------------------
;; Core Plug API

(defprotocol IPlug
  (injected-arg [this])
  (setup! [this])
  (cleanup! [this]))

(defmulti make-plug ^IPlug (fn [spec arg] (first spec)))


(defn pluggable
  "Uses specs (a seq of clauses, which are vectors starting with a keyword) to transform a stateful Reagent component into a 'plugged' component.
The arguments the component will receive are not those passed by the parent component, but 'injected' arguments that have a stateful relationship to them.

See <lang-results-cpnt> below for an example."
  [specs cpn]
  (fn [& args]
    (let [plugs (map (fn [arg spec] (if (nil? spec) nil (make-plug spec arg))) args specs)]
      (r/create-class
        {:display-name "pluggable-wrapper"
         :component-will-mount
         (fn [this]
           (->> plugs (remove nil?) (map setup!) doall))
         :component-will-unmount
         (fn [this]
           (->> plugs (remove nil?) (map cleanup!) doall))
         :reagent-render
         (fn [& args]
           (.log js/console "Rendering again")
           (let [new-args (->> (concat plugs (repeat nil))
                               (map (fn [arg plug]
                                      (if (nil? plug) arg (injected-arg plug))) args)
                               )]
             (into [cpn] new-args)))
         }))))

;; ------------------------------------------------------------------------
;; Some implementations

(defrecord Plug [injected setup! cleanup!]
  IPlug
  (injected-arg [this] (:injected this))
  (setup! [this] ((:setup! this)))
  (cleanup! [this] ((:cleanup! this))))

;; the initial argument is a mult; the injected argument is a channel tapped into this mult
(defmethod make-plug ::a/tap [[_ & [chan-factory]] mult]
  (let [c (if chan-factory (chan-factory) (a/chan))]
    (->Plug c #(a/tap mult c false) #(a/untap mult c))))

(def ^:private gen-watch-key
  (let [next-int (atom 0)]
    (fn [] (keyword "reagent-plug.core" (str "watch-key-" (swap! next-int inc))))))
;; the initial argument is an IRef, the injected argument is a core.async channel that receives updates from the IRef
(defmethod make-plug ::a/atom-watch [[_ & [chan-factory]] atm]
  (let [c ((or chan-factory a/chan))
        key (gen-watch-key)]
    (->Plug c
            #(add-watch atm key (fn [& args] (a/put! c args)))
            #(remove-watch atm key))
    ))

;; the initial argument is not used; the injected argument is a channel which will be closed when the component unmounts.
(defmethod make-plug ::a/local-chan [[_ & [chan-factory]] _]
  (let [c ((or chan-factory a/chan))]
    (->Plug c #(do nil) #(a/close! c))))

;; the initial argument is a ratom; the injected argument is a cursor into some path of this ratom; when the component unmounts, the value at this paths will be cleared.
(defmethod make-plug ::r/cursor [[_ path] ratom]
  (let [curs (r/cursor ratom path)]
    (->Plug curs #(do nil) #(reset! curs nil))))

;; you could also imagine implementations for EventTargets, bacon.js streams etc.


;; -------------------------
;; EXAMPLE: translating a sentence to different languages

(comment
  "For an example usage of " pluggable,
  "you can go have a look at " <lang-results-cpnt>,
  "which is a stateful component that has heavy setup an cleanup phases without any explicit call to React Lifecycle Methods")

(comment
  "Some code conventions to help readability:"
  <name> "is for Reagent Components"
  =name= "is for core.async channels"
  name-a "is for atoms"
  name-c "is for Reagent Cursors"
  )

(enable-console-print!)

(defn find-translation "Calls a Web Service to fetch the translation for a sentence" [target-language sentence]
  (let [=ret= (a/chan)]
    (.log js/console "fetching a translation...")
    (ajx/GET "http://api.mymemory.translated.net/get"
             {:response-format :json :format :json :keywords? true
              :params {:q sentence
                       :langpair (str "en" "|" target-language)}
              :handler #(a/put! =ret= %)
              :error-handler #(a/close! =ret=)})
    =ret=))

(defn rarefied "Small core async utility, to avoid receiving too many events from a channel.
Returns a channel piped to =in= that will periodically return the last element that went through =in=" [=in= t]
  (let [=middle= (a/chan (a/sliding-buffer 1))
        =out= (a/chan)]
    (go-loop [v (a/<! =middle=)]
      (if (some? v)
        (do
          (a/>! =out= v)
          (a/<! (a/timeout t))
          (recur (a/<! =middle=)))
        (a/close! =out=)))
    (a/pipe =in= =middle=)
    =out=))

(declare
  <top-cpnt>
  <english-input>
  <available-languages>
  <lang-results-cpnt>)

(def languages [{:id "fr" :name "French"}
                {:id "it" :name "Italian"}
                {:id "de" :name "Deutsch"}])

(defn <top-cpnt> []
  (let [state-a (r/atom {:languages [(first languages)]
                         :sentence "Hello"})
        sentence-c (r/cursor state-a [:sentence])]
    (fn []
      [:div.top-cpnt
       [:h2 "Translate to several languages"]
       [<english-input> sentence-c]

       [<available-languages> state-a]

       [:div
        [:h3 "Translations"]
        [:hr]
        (for [{:keys [id] :as lang} (:languages @state-a)]
          ^{:key id}
          [:div
           [<lang-results-cpnt> lang state-a sentence-c sentence-c nil]
           [:hr]])]
       ]
      )))

(defn <english-input> [sentence-c]
  [:div.english-input
   [:span "English text: "]
   [:input {:type "text" :value @sentence-c :on-change #(reset! sentence-c (-> % .-target .-value))}]])

(defn <available-languages> [state-a]
  [:div.available-languages
   [:h3 "Available languages"]
   [:table.table
    [:tbody
     (->> languages (remove (set (:languages @state-a)))
          (map (fn [{:keys [id name] :as l}]
                 ^{:key id}
                 [:tr
                  [:td name]
                  [:td [:button.btn.btn-primary {:on-click (fn [_] (swap! state-a update :languages #(conj % l)))}
                        "Add"]]]))
          doall)]]])

(defn- start-translate-machine! "Starts a process that manages the state of a <lang-result-cpnt>"
  [lang sentence-c =sentence-changes= =c= local-state]
  (let [=sentence-changes= (rarefied =sentence-changes= 1000)]
    (go
      #_ (.log js/console (str "Starting loop for" (:name lang) "..."))
      (loop [sentence @sentence-c
             =translate-result= (find-translation (:id lang) sentence)]
        (match [(a/alts! [=sentence-changes= =c= =translate-result=])]
          [[nil =translate-result=]] (.log js/console "Failed to fetch translation")
          [[nil _]] nil ;; thanks to pluggable a channel that closes means the component unmounted
          [[data =translate-result=]] (do (swap! local-state assoc :translation data :pending false)
                                          (recur sentence (a/chan)))
          [[sentence =sentence-changes=]] (recur sentence (a/chan)) ;; déjà vu
          [[new-sentence =sentence-changes=]] (do (swap! local-state assoc :translation nil :pending true)
                                                  (recur new-sentence (find-translation (:id lang) new-sentence)))
          )))
    ))

(def <lang-results-cpnt>
  (pluggable
        [nil    nil     [::a/atom-watch #(a/chan 1 (map last))]        nil        [::a/local-chan]] ;; specs
    (fn [lang state-a            =sentence-changes=                 sentence-c           =c=] ;; here we declare a regular Reagent stateful component (2 nested fns)

      ;; !!!!!!!!!!!!!!!!!!!!
      ;; EXPLANATION HERE!
      (comment
        "Thanks to " pluggable ":"

        * =sentence-changes= " is a core.async channel that receives the updated versions of the " sentence-c "cursor when it changes"
        * =c= "is a channel which will be closed when" <lang-results-cpnt> "unmounts"
        * lang, state-a "and" sentence-c "are passed unaffected from the parent component.")
      ;; !!!!!!!!!!!!!!!!!!!!

      (let [local-state (r/atom {:pending true :translation nil})]
        (start-translate-machine! lang sentence-c =sentence-changes= =c= local-state)

        (fn []
          [:div
           [:h4 (:name lang)]
           (if (:pending @local-state)
             [:div "fetching translation..."]
             [:div "translation : " (str (get-in @local-state [:translation :responseData :translatedText]))])
           [:div [:button.btn.btn-danger {:on-click (fn [_] (swap! state-a update :languages #(remove #{lang} %)))}
                  "Supprimer"]]]))
      )))

;; here is a less noisy version using some macro sugar
(defplugged <lang-results-cpnt-bis>
  [lang
   state-a
   (=sentence-changes= [::a/atom-watch #(a/chan 1 (map last))])
   sentence-c
   (=c= [::a/local-chan])]
  (let [local-state (r/atom {:pending true :translation nil})]
    (start-translate-machine! lang sentence-c =sentence-changes= =c= local-state)
    (fn []
      [:div
       [:h4 (:name lang)]
       (if (:pending @local-state)
         [:div "fetching translation..."]
         [:div "translation : " (str (get-in @local-state [:translation :responseData :translatedText]))])
       [:div [:button.btn.btn-danger {:on-click (fn [_] (swap! state-a update :languages #(remove #{lang} %)))}
              "Supprimer"]]])))


(defn home-page []
  [:div.container
   [:h1 "Welcome to reagent-plug"]
   [<top-cpnt>]])

;; -------------------------
;; Initialize app

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (mount-root))
