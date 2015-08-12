This repository explores some ideas about a certain kind of stateful Reagent components, which I call "pluggable" components here.

We provide a concise, declarative way of writing Reagent components that have setup and cleanup phases, ***without explicitely writing React lifecycle methods***.
This can be useful, for example, to write components that communicate through core.async channels.

### Basic Example

Imagine you have a core.async `pub`, and you want to write a Reagent component that subscribes to it when it mounts, and unsubscribes when it unmounts. 
In traditional Reagent style you could write it this way:

```clojure
(defn my-component [my-pub]
  (let [local-chan (async/chan)]
    (go (start-a-local-process-using local-chan) (etc))
    (r/create-class
         {:component-will-mount
          (fn [this] (async/sub my-pub :my-topic local-chan))
          :component-will-unmount
          (fn [this] 
            (async/unsub my-pub :my-topic local-chan)
            (async/close! local-chan))
          :reagent-render
          (fn []
            [:div "html view blah blah blah"])
          })))
```

Instead, you would write:

```clojure
(def my-component
  (pluggable 
        [ [::async/sub :my-topic] ]
    (fn [      local-chan         ]
      (go (start-a-local-process-using local-chan) (etc))
      (fn []
        [:div "html view blah blah blah"]))
    ))
```

This way you don't have to write the cleanup logic; it's already included in the `::async/sub` 'recipe'.

In both cases, you would pass the sub from the parent component the same way:

```clojure
(defn parent-component []
  [my-component my-pub])
```

## How does it work ? 

The `pluggable` function wraps a component so that some of the arguments it receives are not those passed by the parent, but other things derived from them, which lifecycle are bound to that of the React component.

In the above example, the component does not receive the `sub`, but a channel that is subscribed to it, and will be unsubscribed and closed as soon as the component unmounts.

## Why would I need it? 

If for some reason you have some components that are connected to the rest of you application / the outside world in a stateful way, and you wnat them to stop affecting your application / clean after themselves as soon as they unmount.

## Extensibility

You can write your own "plug recipes" using the `make-plug` multimethods. For example, writing the `::async/pubsub` recipe from the example above is as easy as:
```clojure
(defmethod make-plug ::async/sub [[_ topic] pub]
  (let [local-chan (a/chan)]
    (->Plug local-chan 
            #(async/sub pub topic local-chan)
            #(do (async/unsub pub topic local-chan) (async/close! local-chan)))))
```

Implementations have been provided in this repository for a few use cases :

* tapping into a `mult`
* getting a Reagent `cursor` from a `ratom`, and resetting the `cursor` to nil when unmounting
* getting a core.async channel that receives the changes from an IRef (using `add-watch`)
* getting a local core.async channel which will be closed when unmounting

## Code walkthrough

There is really not much to it. The [core API](https://github.com/vvvvalvalval/reagent-pluggable-components-poc/blob/234bb2f1c15aecdd12f2ceb5ed6564cacd0d5e3a/src/cljs/reagent_plug/core.cljs#L16) is somewhat 30 lines.
About as much for defining the sample implementations described above.

## A more advanced example.

In this repository comes [a less trivial example](https://github.com/vvvvalvalval/reagent-pluggable-components-poc/blob/234bb2f1c15aecdd12f2ceb5ed6564cacd0d5e3a/src/cljs/reagent_plug/core.cljs#L90). 
It is a small app that fetches translations for the text you type in English in several languages.
Fetching a translation is a component-local process which starts and stops automatically as you add and remove languages. 

You can see it in action [here](http://reagent-plug-poc.s3-website-us-east-1.amazonaws.com).
