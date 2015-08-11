(ns reagent-plug.core.macros)

;; and here's for some sugar
(defmacro plugged-cpnt [spec-bindings & body]
  (let [specs (->> spec-bindings (map #(if (list? %) (second %) nil)) vec)
        args (->> spec-bindings (map #(if (list? %) (first %) %)) vec)]
    `(reagent-plug.core/pluggable ~specs (fn ~args ~@body))))

(defmacro defplugged [name & args]
  (let [docstring? (string? (first args))
        args2 (cond docstring? (rest args) :else args)]
    `(def ~name ~@(if docstring? [(first args)] ()) (plugged-cpnt ~@args2))))
