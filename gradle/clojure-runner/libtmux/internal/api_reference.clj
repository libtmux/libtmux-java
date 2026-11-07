(ns libtmux.internal.api-reference
  "Build-only native API reference from public var metadata."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- source-link [module namespace]
  (str "../../" module "/src/main/clojure/"
       (-> (str namespace) (str/replace "." "/") (str/replace "-" "_"))
       ".clj"))

(defn- prose [text]
  (str/replace (str/trim text) #"\n  " "\n"))

(defn- public-functions [namespace]
  (->> (ns-publics namespace)
       vals
       (map meta)
       ;; Compiler-interned proxy class caches have no source declaration.
       (filter :file)
       (remove :no-doc)
       (sort-by (comp str :name))
       (mapv (fn [{:keys [name doc arglists] :as metadata}]
               (when (or (str/blank? doc) (empty? arglists))
                 (throw (ex-info "Public Clojure vars need docstrings and arglists"
                                 {:namespace namespace :var name})))
               metadata))))

(defn- namespace-section [namespace]
  (let [description (:doc (meta (the-ns namespace)))
        functions (public-functions namespace)]
    (when (or (str/blank? description) (empty? functions))
      (throw (ex-info "Public Clojure namespaces need documentation and functions"
                      {:namespace namespace})))
    (str "## [`" namespace "`][" namespace "]\n\n"
         (prose description) "\n\n"
         (apply str
                (for [{:keys [name doc arglists]} functions]
                  (str "### [`" namespace "/" name "`][" namespace "]\n\n"
                       (str/join "\n\n"
                                 (for [arguments arglists]
                                   (str "`" (pr-str (cons name arguments)) "`")))
                       "\n\n" (prose doc) "\n\n"))))))

(defn- reference [module namespaces]
  (doseq [namespace namespaces] (require namespace))
  (str "# " module " API\n\n"
       "Generated from public namespace and var metadata; do not edit by hand.\n"
       "Each heading links to its current Clojure source.\n\n"
       "Read the [guide](clojure.md) for runnable programs and ownership.\n"
       "The [captured-value reference](clojure-reference.md) documents map keys.\n"
       "Functions ending in `!` may cross the external-state boundary.\n\n"
       "## Namespaces\n\n"
       (str/join "\n" (for [namespace namespaces]
                        (str "- [`" namespace "`](#"
                             (str/replace (str namespace) "." "") ")")))
       "\n\n" (apply str (map namespace-section namespaces))
       (str/join "\n" (for [namespace namespaces]
                        (str "[" namespace "]:\n  " (source-link module namespace))))
       "\n"))

(defn -main [mode destination]
  (let [module (System/getProperty "libtmux.clojure.reference.module")
        configured (System/getProperty "libtmux.clojure.main-namespaces")]
    (when (or (str/blank? module) (str/blank? configured) (str/blank? destination))
      (throw (ex-info "Native reference needs a module, namespaces and destination" {})))
    (let [namespaces (mapv symbol (str/split configured #","))
          expected (reference module namespaces)
          file (io/file destination)]
      (case mode
        "write" (spit file expected)
        "check" (when-not (and (.isFile file) (= expected (slurp file)))
                  (throw (ex-info "Stale native Clojure API reference; run updateClojureApiReference"
                                  {:module module :reference (.getName file)})))
        (throw (ex-info "Expected reference mode check or write" {:mode mode})))
      (println "Native Clojure API reference:" mode module (count namespaces) "namespaces"))))
