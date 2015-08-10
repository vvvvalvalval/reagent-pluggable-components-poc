(ns reagent-plug.prod
  (:require [reagent-plug.core :as core]))

;;ignore println statements in prod
(set! *print-fn* (fn [& _]))

(core/init!)
