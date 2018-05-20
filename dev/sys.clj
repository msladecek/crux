(ns sys
  (:require [clojure.tools.namespace.repl :as tn])
  (:import [clojure.lang IDeref Var$Unbound]
           [java.io Closeable]))

;; Inspired by
;; https://medium.com/@maciekszajna/reloaded-workflow-out-of-the-box-be6b5f38ea98

(def instance)
(def init)

(defn ^Closeable closeable [value close-fn]
  (reify
    IDeref
    (deref [_]
      value)
    Closeable
    (close [_]
      (close-fn value))))

(defn ^Closeable closeable-future-call [f]
  (let [done? (promise)]
    (closeable
     (future
       (try
         (f)
         (finally
           (deliver done? true))))
     (fn [this]
       (try
         (when (future-cancel this)
           @done?)
         (when-not (future-cancelled? this)
           @this))))))

(defn start []
  (alter-var-root
   #'instance (fn [instance]
                (cond
                  (not (bound? #'init))
                  (throw (IllegalStateException. "init not set."))

                  (or (nil? instance)
                      (instance? Var$Unbound instance))
                  (cast Closeable (init))

                  :else
                  (throw (IllegalStateException. "Already running.")))))
  :started)

(defn stop []
  (when (and (bound? #'instance)
             (not (nil? instance)))
    (alter-var-root #'instance #(.close ^Closeable %)))
  :stopped)

(defn clear []
  (alter-var-root #'instance (constantly nil)))

(defn reset []
  (stop)
  (let [result (tn/refresh :after 'sys/start)]
    (if (instance? Throwable result)
      (throw result)
      result)))

(defn with-system-var [do-with-system-fn target-var]
  (fn [system]
    (try
      (alter-var-root target-var (constantly system))
      (do-with-system-fn system)
      (finally
        (alter-var-root target-var (constantly nil))))))

(defn with-system-promise [do-with-system-fn promise]
  (fn [system]
    (deliver promise system)
    (do-with-system-fn system)))

(defn make-init-fn [with-system-fn do-with-system-fn system-var]
  (fn []
    (let [started? (promise)
          instance (closeable-future-call
                    #(-> do-with-system-fn
                         (with-system-promise started?)
                         (with-system-var system-var)
                         (with-system-fn)))]
      (while (not (or (deref @instance 100 false)
                      (deref started? 100 false))))
      instance)))
