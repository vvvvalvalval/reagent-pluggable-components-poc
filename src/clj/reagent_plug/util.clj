(ns reagent-plug.util)

(defmacro spy [exp]
  (let [text (str exp " : ")]
    `(let [ret# ~exp] (.log js/console ~text (cljs->js ret#)) ret#)))
