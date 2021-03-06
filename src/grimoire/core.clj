(ns grimoire.core
  (:refer-clojure :exclude [replace])
  (:require [clojure.java.io :refer :all]
            [clojure.string :refer [lower-case upper-case replace-first replace]]
            [cd-client.core :as cd])
  (:import [java.io LineNumberReader InputStreamReader PushbackReader]
           [clojure.lang RT]))

;; Intended file structure output
;;--------------------------------------------------------------------
;;
;; Do we want to kick out raw HTML, or do we want to kick out markdown
;; that we can run through jekyll or something else to use pygments?
;; I'm personally a fan of how pygments treats the Clojure code I
;; throw at it for my blog.
;;
;; It may be worthwhile to provide a litte Javascript snippet that can
;; munge a given fully qualified name if we have a search feature at
;; all.
;;
;; Everything under /_include/ is where the data goes
;;
;; /<clojure-version>/ is to be YAML templates built from include
;; data. There may even be a way to get jekyll to build all that.
;;
;; /
;; /_include/<clojure-version>/<namespace>/
;; /_include/<clojure-version>/<namespace>/<symbol>/source.md
;; /_include/<clojure-version>/<namespace>/<symbol>/docs.md
;; /_include/<clojure-version>/<namespace>/<symbol>/examples.md
;; /_include/<clojure-version>/<namespace>/<symbol>/index.md
;; /<clojure-version>/
;; /<clojure-version>/<namespace>/
;; /<clojure-version>/<namespace>/<symbol>/

(defn var->name [v]
  {:pre [(var? v)]}
  (-> v .sym str))

(defn var->ns [v]
  {:pre [(var? v)]}
  (-> v .ns ns-name str))

(defn macro? [v]
  {:pre [(var? v)]}
  (:macro (meta v)))

;; clojure.repl/source-fn
(defn source-fn
  "Returns a string of the source code for the given symbol, if it can
  find it.  This requires that the symbol resolve to a Var defined in
  a namespace for which the .clj is in the classpath.  Returns nil if
  it can't find the source.  For most REPL usage, 'source' is more
  convenient.

  Example: (source-fn #'clojure.core/filter)"
  [v]
  {:pre [(var? v)]
   :post [(string? %)]}
  (or (when-let [filepath (:file (meta v))]
        (when-let [strm (.getResourceAsStream (RT/baseLoader) filepath)]
          (with-open [rdr (LineNumberReader. (InputStreamReader. strm))]
            (dotimes [_ (dec (:line (meta v)))] (.readLine rdr))
            (let [text (StringBuilder.)
                  pbr (proxy [PushbackReader] [rdr]
                        (read [] (let [i (proxy-super read)]
                                   (.append text (char i))
                                   i)))]
              (read (PushbackReader. pbr))
              (str text)))))
      ";; Source not found!\n;; Black magic likely in this var's definition"))

(defn lq [& more]
  (str "{%% " (reduce str (interpose " " more)) " %%}\n"))

(defn render-yaml
  [mapping]
  (str "---\n"
       (->> mapping
            (map (fn [[k v]] (str k ": " v "\n")))
            (reduce str))
       "---\n"))

(defn trim-dot
  [s]
  (replace-first s "./" ""))

(def prior-clojure-version
  {;; 1.3.0 -> nil
   "1.4.0" "1.3.0"
   "1.5.0" "1.4.0",
   "1.6.0" "1.5.0"})

(defn my-munge [s]
  (-> s
      (replace "*" "_STAR_")
      (replace "?" "_QMARK_")
      (replace "." "_DOT_")
      (replace "<" "_LT_")
      (replace ">" "_GT_")
      (replace "-" "_DASH_")
      (replace "/" "_SLASH_")
      (replace "!" "_BANG_")
      (replace "=" "_EQ_")
      (replace "+" "_PLUS_")
      (replace "'" "_SQUOTE_")
      (replace #"^_*" "")
      (replace #"_*$" "")))

(defn write-example
  [i {:keys [body] :as example}]
  (str "### Example " i "\n"
       "[permalink](#example-" i ")\n"
       "\n"
       "{% highlight clojure linenos %}\n"
       "{% raw %}\n"
       (replace body #"[\t ]+\n" "\n")
       "{% endraw %}\n"
       "{% endhighlight %}\n\n\n"))

(defn write-docs-for-var
  [[ns-dir inc-dir] var]
  {:pre [(var? var)]}
  (let [namespace                         (-> var .ns ns-name str)
        raw-symbol                        (-> var .sym str)
        symbol                            (my-munge raw-symbol)
        md-symbol                         (replace raw-symbol "*" "\\*")
        {:keys [arglists doc] :as meta}   (meta var)
        {:keys [major minor incremental]} *clojure-version*
        version-str                       (format "%s.%s.%s" major minor incremental)]
    (let [sym-inc-dir (file inc-dir symbol)]
      (.mkdir sym-inc-dir)

      ;; write docstring file
      (let [inc-doc-file (file sym-inc-dir "docs.md")]
        (->> (format (str "## Arities\n"
                          "%s\n\n"
                          "## Documentation\n"
                          "{%%raw%%}\n"
                          "%s\n"
                          "{%%endraw%%}\n")
                     (->> arglists
                          (interpose \newline)
                          (reduce str))
                     doc)
             (spit inc-doc-file)))

      (when (fn? @var)
        ;; write source file
        (let [inc-src-file (file sym-inc-dir "src.md")]
          (->> (format (str (lq "highlight" "clojure" "linenos")
                            "%s\n"
                            (lq "endhighlight"))
                       (source-fn var))
               (spit inc-src-file))))

      (let [ex-file (file sym-inc-dir "examples.md")]
        ;; ensure the examples file
        (when-not (.exists ex-file)
          (->> (str (let [v (prior-clojure-version version-str)
                          i (str v "/" namespace "/" symbol "/examples.md")
                          f (str "./_includes/" i)]
                      (when (.exists (file f))
                        (str "{% include " i " %}\n")))

                    (when (= version-str "1.4.0")
                      (if-let [examples (-> (cd/examples-core namespace raw-symbol) :examples)]
                        (->> examples 
                             (map-indexed write-example)
                             (reduce str))))

                    (when (= version-str "1.6.0")
                      (str "\n"
                           "[Please add examples!](https://github.com/arrdem/grimoire/edit/master/"
                                                   ex-file ")\n")))
               (spit ex-file)))))

    ;; write template files
    ;; /<clojure-version>/<namespace>/<symbol>.md
    (let [dst-dir (file (str ns-dir "/" symbol))
          dst-file (file dst-dir "index.md")]
      (.mkdir dst-dir)

      (->> (str (render-yaml [["layout"    "fn"]
                              ["namespace" namespace]
                              ["symbol"    (pr-str md-symbol)]])
                (format "\n# [%s](../)/%s\n\n"
                        namespace
                        md-symbol)
                (lq "include" (trim-dot (str ns-dir "/" symbol "/docs.md")))
                "\n##Examples\n\n"
                (lq "include" (trim-dot (str ns-dir "/" symbol "/examples.md")))
                (if (fn? @var)
                  (str "## Source\n"
                       (lq "include" (trim-dot (str ns-dir "/" symbol "/src.md"))))
                  "")
                "\n")
           (format)
           (spit dst-file)))))

(defn var->link
  [v]
  {:pre  [(var? v)]
   :post [(string? %)]}
  (format "[%s](%s)\n"
          (replace (var->name v) "*" "\\*")
          (str "./" (-> v var->name my-munge) "/")))

(defn index-vars
  [var-seq]
  {:pre  [(every? var? var-seq)]
   :post [(string? %)]}
  (let [f      (comp first var->name)
        blocks (group-by f var-seq)
        blocks (sort-by first blocks)]
    (->> (for [[heading vars] blocks]
           (str (format "### %s\n\n" (-> heading upper-case str))
                (->> (for [var (sort-by var->name vars)]
                       (var->link var))
                     (reduce str))))
         (interpose "\n\n")
         (reduce str))))

(defn write-docs-for-ns
  [dirs ns]
  (let [[version-dir include-dir] dirs
        ns-vars                   (map second (ns-publics ns))
        macros                    (filter macro? ns-vars)
        fns                       (filter #(and (fn? @%1)
                                                (not (macro? %1)))
                                          ns-vars)
        vars                      (filter #(not (fn? @%1)) ns-vars)]
    (let [version-ns-dir  (file version-dir (name ns))
          include-ns-dir  (file include-dir (name ns))]
      (.mkdir version-ns-dir)
      (.mkdir include-ns-dir)

      ;; write per symbol docs
      (doseq [var ns-vars]
        (try
          (write-docs-for-var [version-ns-dir include-ns-dir] var)
          (catch java.lang.AssertionError e
            (println "Warning: Failed to write docs for" var))))

      ;; write namespace index
      (let [index-file (file version-ns-dir "index.md")
            index-inc-file (file include-ns-dir "index.md")]

        (when-not (.exists index-inc-file)
          (->> (str "No namespace specific documentation!\n"
                    "\n"
                    "[Please add commentary!](https://github.com/arrdem/grimoire/edit/master/"
                                              index-inc-file ")\n\n")
               (spit index-inc-file)))

        (let [f (file index-file)]
          (->> (str (render-yaml [["layout" "ns"]
                                  ["title"  (name ns)]])

                    (str "{% markdown " version-ns-dir  "/index.md %}\n\n")

                    (when macros
                      (str "## Macros\n\n"
                           (index-vars macros)))

                    "\n\n"

                    (when vars
                      (str "## Vars\n\n"
                           (index-vars vars)))

                    "\n\n"
                    
                    (when fns
                      (str "## Functions\n\n"
                           (index-vars fns))))
               (spit f))))))

  (println "Finished" ns)
  nil)

(def namespaces
  [;; Clojure "core"
   'clojure.core
   'clojure.data
   'clojure.edn
   'clojure.instant
   'clojure.pprint
   'clojure.reflect
   'clojure.repl
   'clojure.set
   'clojure.stacktrace
   'clojure.string
   'clojure.template
   'clojure.test
   'clojure.uuid
   'clojure.walk
   'clojure.xml
   'clojure.zip

   ;; Clojure JVM host interop
   'clojure.java.browse
   'clojure.java.io
   'clojure.java.javadoc
   'clojure.java.shell

   ;; Clojure test
   'clojure.test.junit
   'clojure.test.tap
   ])

(defn -main
  []
  (cd/set-web-mode!)

  (let [{:keys [major minor incremental]} *clojure-version*
        version-str                       (format "%s.%s.%s" major minor incremental)]
    (println version-str)
    (let [version-dir (file version-str)
          include-dir (file (str "_includes/" version-str))]
      (.mkdir version-dir)
      (.mkdir include-dir)

      (println "Made root folders...")

      (doseq [n namespaces]
        (when-not (and (= n 'clojure.edn)
                       (= version-str "1.4.0"))
          (require n)
          (write-docs-for-ns [version-dir include-dir] n)))

      (let [version-inc-file (file include-dir "index.md")]
        (when-not (.exists version-inc-file)
          (->> (str "No release specific documentation!\n"
                    "\n"
                    "[Please add changelog!](https://github.com/arrdem/grimoire/edit/master/"
                                             version-inc-file ")\n\n")
               (spit version-inc-file))))

      (let [version-file (file version-dir "index.md")]
        (->> (str (render-yaml [["layout"  "release"]
                                ["version" version-str]])
                  "\n"
                  "## Release information\n"
                  "\n"
                  (str "{% markdown " version-file " %}\n")
                  "\n"
                  "## Namespaces\n"
                  "\n"
                  (->> namespaces
                       (filter (fn [n]
                                 (not (and (= n 'clojure.edn)
                                           (= version-str "1.4.0")))))
                       (map name)
                       (map #(format "- [%s](./%s/)\n" %1 %1))
                       (reduce str)))
             (spit version-file))))))
