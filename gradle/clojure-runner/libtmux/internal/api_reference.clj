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

(def ^:private api-documents
  {'libtmux.core "clojure-api.md" 'libtmux.data "clojure-api.md"
   'libtmux.async "clojure-api.md" 'libtmux.control "clojure-api.md"
   'libtmux.core-async "clojure-core-async-api.md"})

(defn- linked-target [namespace text]
  (let [target (symbol text)
        owner (symbol (or (clojure.core/namespace target) (str namespace)))
        namespace-link? (contains? api-documents target)
        owner (if namespace-link? target owner)]
    (when (and (contains? api-documents owner) (not (find-ns owner))
               (io/resource (str (-> (str owner) (str/replace "." "/")
                                     (str/replace "-" "_")) ".clj")))
      (require owner))
    (let [metadata (when (find-ns owner)
                     (meta (get (ns-publics owner) (symbol (name target)))))]
      (when-not (and (contains? api-documents owner)
                     (or namespace-link?
                         (and (:file metadata) (:doc metadata) (not (:no-doc metadata)))))
        (throw (ex-info "Unknown public Clojure documentation reference"
                        {:namespace namespace :reference text})))
      {:namespace owner :anchor (if namespace-link? (str owner) (str owner "/" (name target)))})))

(defn- escape-html [text]
  (str/escape (str text) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn- doc-prose [namespace text format]
  (let [text (prose text)
        matcher (re-matcher #"\[\[([^\]\r\n]+)\]\]|`([^`\r\n]+)`" text)
        encode (if (= :html format) escape-html identity)]
    (loop [offset 0 result ""]
      (if (.find matcher)
        (let [reference (.group matcher 1)
              code (.group matcher 2)
              rendered
              (if reference
                (let [{:keys [anchor]} (linked-target namespace reference)]
                  (if (= :html format)
                    (str "<a href=\"#" (escape-html anchor) "\"><code>"
                         (escape-html reference) "</code></a>")
                    (str "[`" reference "`][api:" anchor "]")))
                (if (= :html format) (str "<code>" (escape-html code) "</code>")
                    (str "`" code "`")))]
          (recur (.end matcher) (str result (encode (subs text offset (.start matcher))) rendered)))
        (str result (encode (subs text offset)))))))

(defn- markdown-prose [namespace text]
  (str/join "\n"
            (mapcat
             (fn [line]
               (loop [words (re-seq #"(?:\[`[^`]+`\]\[api:[^\]]+\]|`[^`]+`|\S)+" line)
                      current "" lines []]
                 (if-let [word (first words)]
                   (if (and (seq current) (> (+ (count current) 1 (count word)) 80))
                     (recur (next words) word (conj lines current))
                     (recur (next words) (str current (when (seq current) " ") word) lines))
                   (conj lines current))))
             (str/split-lines (doc-prose namespace text :markdown)))))

(defn- namespace-links [namespace]
  (for [doc (cons (:doc (meta (the-ns namespace))) (map :doc (public-functions namespace)))
        [_ reference] (re-seq #"\[\[([^\]\r\n]+)\]\]" doc)]
    (linked-target namespace reference)))

(defn- namespace-section [namespace]
  (let [description (:doc (meta (the-ns namespace)))
        functions (public-functions namespace)]
    (when (or (str/blank? description) (empty? functions))
      (throw (ex-info "Public Clojure namespaces need documentation and functions"
                      {:namespace namespace})))
    (str "## [`" namespace "`][" namespace "]\n\n"
         (markdown-prose namespace description) "\n\n"
         (apply str
                (for [{:keys [name doc arglists]} functions]
                  (str "### [`" namespace "/" name "`][" namespace "]\n\n"
                       (str/join "\n\n"
                                 (for [arguments arglists]
                                   (str "`" (pr-str (cons name arguments)) "`")))
                       "\n\n" (markdown-prose namespace doc) "\n\n"))))))

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
       "\n"
       (str/join "\n" (for [{owner :namespace anchor :anchor}
                               (sort-by :anchor (distinct (mapcat namespace-links namespaces)))]
                        (str "[api:" anchor "]:\n  "
                             (when-not ((set namespaces) owner) (api-documents owner))
                             "#" (str/replace anchor #"[^\p{L}\p{N}_-]" ""))))
       "\n"))

(defn- html-page [title body]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" (escape-html title) "</title><style>"
       ":root{color-scheme:light dark;font:16px/1.6 system-ui,sans-serif}"
       "body{max-width:80ch;margin:2rem auto;padding:0 1.5rem}"
       "a{color:light-dark(#0052a4,#9acbff)}code,pre{font-family:ui-monospace,monospace}"
       "pre{overflow:auto;padding:1rem;background:light-dark(#f4f6f8,#1b2028)}"
       ".doc{white-space:pre-wrap}section{border-top:1px solid #8886;margin-top:2rem}"
       ".source-line{scroll-margin-top:1rem}.source-line:target{background:#ffb30040}"
       ".line-number{display:inline-block;width:4ch;color:inherit;opacity:.6;text-decoration:none;user-select:none}"
       "h2,h3{line-height:1.3;scroll-margin-top:1rem}</style></head><body>"
       body "</body></html>\n"))

(defn- source-page! [directory file]
  (let [source (io/resource file)
        destination (io/file directory "source" (str file ".html"))]
    (when-not source
      (throw (ex-info "Native reference source is missing" {:source file})))
    (.mkdirs (.getParentFile destination))
    (spit destination
          (html-page file
                     (str "<p><a href=\"" (apply str (repeat (count (str/split file #"/")) "../"))
                          "index.html\">API reference</a></p><h1>" (escape-html file) "</h1><pre>"
                          (apply str
                                 (map-indexed
                                  (fn [index line]
                                    (let [number (inc index)]
                                      (str "<span class=\"source-line\" id=\"L" number "\">"
                                           "<a class=\"line-number\" href=\"#L" number "\">" number
                                           "</a>" (escape-html line) "</span>\n")))
                                  (str/split-lines (slurp source))))
                          "</pre>")))))

(defn- linked-namespaces [namespaces]
  (loop [pending (seq namespaces) ordered (vec namespaces)]
    (if-let [namespace (first pending)]
      (let [targets (map :namespace (namespace-links namespace))
            additional (remove (set ordered) (distinct targets))]
        (recur (concat (rest pending) additional) (into ordered additional)))
      ordered)))

(defn- html-reference! [module namespaces directory]
  (.mkdirs (io/file directory))
  (let [namespaces (linked-namespaces namespaces)
        sections
        (for [namespace namespaces
              :let [functions (public-functions namespace)]]
          (str "<h2 id=\"" namespace "\">" namespace "</h2><p class=\"doc\">"
               (doc-prose namespace (:doc (meta (the-ns namespace))) :html) "</p><ul>"
               (apply str (for [{:keys [name]} functions]
                            (str "<li><a href=\"#" namespace "/" name "\">"
                                 (escape-html name) "</a></li>"))) "</ul>"
               (apply str
                      (for [{:keys [name doc arglists file line]} functions]
                        (str "<section id=\"" namespace "/" name "\"><h3>"
                             (escape-html (str namespace "/" name)) "</h3><pre>"
                             (str/join "\n" (for [arguments arglists]
                                               (escape-html (pr-str (cons name arguments)))))
                             "</pre><p class=\"doc\">" (doc-prose namespace doc :html) "</p>"
                             "<p><a href=\"source/" file ".html#L" line "\">Source</a></p></section>")))))]
    (doseq [file (distinct (for [namespace namespaces
                                function (public-functions namespace)] (:file function)))]
      (source-page! directory file))
    (spit (io/file directory "index.html")
          (html-page (str module " API")
                     (str "<h1>" (escape-html module) " API</h1>"
                          "<p>Native Clojure namespaces, function arities and docstrings. "
                          "Linked dependency namespaces are included for offline navigation. "
                          "Source links open the code packaged with this reference.</p><nav><ul>"
                          (apply str (for [namespace namespaces]
                                       (str "<li><a href=\"#" namespace "\">" namespace "</a></li>")))
                          "</ul></nav>" (apply str sections))))))

(defn -main [mode destination]
  (let [module (System/getProperty "libtmux.clojure.reference.module")
        configured (System/getProperty "libtmux.clojure.main-namespaces")]
    (when (or (str/blank? module) (str/blank? configured) (str/blank? destination))
      (throw (ex-info "Native reference needs a module, namespaces and destination" {})))
    (let [namespaces (mapv symbol (str/split configured #","))
          expected (reference module namespaces)
          file (io/file destination)]
      (case mode
        "html" (html-reference! module namespaces destination)
        "write" (spit file expected)
        "check" (when-not (and (.isFile file) (= expected (slurp file)))
                  (throw (ex-info "Stale native Clojure API reference; run updateClojureApiReference"
                                  {:module module :reference (.getName file)})))
        (throw (ex-info "Expected reference mode check, write or html" {:mode mode})))
      (println "Native Clojure API reference:" mode module (count namespaces) "namespaces"))))
