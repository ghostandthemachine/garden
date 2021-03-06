(ns garden.compiler
  (:require [clojure.string :as s]
            [clojure.math.combinatorics :refer [cartesian-product]]
            [garden.util :as u]
            garden.units
            garden.types)
  (:import garden.types.CSSFunction
           garden.units.CSSUnit))

(defprotocol CSSRenderer
  (render-css [this]
    "Convert a Clojure data type in to a string of CSS."))

;; Meta is allowed to be used as notation for a media query, because
;; of this the compilation process is divided in to two steps: compiling
;; rules that do not belong to a media query, and compiling rules that
;; do.

;; As the stylesheet is compiled, rules tagged with meta, containing
;; keys which are valid to use in a media expression, are stored in the
;; `media-query-rules` vector. The rule is stored as a triple of the
;; media query expression (a map), rules (a vector), and the context
;; for which the rules belong (either nil or list). 

(def ^{:private true
       :doc "A vector containing triples of media-query, rules, and context"}
  media-query-rules (atom []))

(defn- add-media-query-rules!
  [query rules context]
  (let [rules (if (seq? rules) (vec rules) (vector rules))]
    (swap! media-query-rules conj [query rules context])))

(defn- ^String indent
  "Return an indented string.

   Ex. (indent 4)
   => \"    \""
  ([]
   (indent (u/indent-level)))
  ([n]
   (reduce str (take n (repeat " ")))))

;;;; Declaration, rule, and stylesheet generation.

(defn- expand-declaration
  "Expands nested properties in declarations.

   Ex. (expand-declaration {:foo {:bar \"baz\"}})
   => {\"foo-bar\" \"baz\"}"
  [declaration]
  (reduce
    (fn [m [prop value]]
      (let [prop (u/to-str prop)
            prefix (fn [[k v]]
                     {(str prop "-" (u/to-str k)) v})]
        (if (and (map? value)
                 (not (u/record? value)))
          (expand-declaration (into m (map prefix value)))
          (assoc m prop value))))
    {}
    declaration)) 

(defn- ^String make-declaration
  "Make a CSS declaration from a double of property and value.

   Ex. (make-declaration [:foo 5])
   => \"foo:5\"
   Ex. (make-declaration [:foo [:bar :baz]])
   => \"foo:bar baz\"
   Ex. (make-declaration [:foo [[:bar :baz]]])
   => \"foo:bar,baz\""
  [[prop v]]
  (let [v (if (sequential? v) (u/space-join v) (u/to-str v))]
    (str (indent) (u/to-str prop) (u/colon) v)))

(defn- ^String make-rule
  "Make a CSS rule from a vector."
  [[selector & declarations]]
  (str (u/to-str selector) (u/left-brace)
       (s/join (u/semicolon) (map render-css declarations))
       (u/right-brace)))

(defn- ^String render-declaration
  "Render a declaration map as a CSS declaration."
  [declaration]
  (->> (expand-declaration declaration)
       (map make-declaration)
       (s/join (u/semicolon))))

(defn ^String extract-reference
  "Extracts the selector portion of a parent selector reference."
  [selector]
  (when-let [reference (re-find #"^&.+|^&$" (u/to-str (last selector)))]
    (apply str (rest reference))))

(defn- expand-selector
  "Expands a selector within the context and returns a new selector."
  [selector context]
  (let [new-context (if (seq context)
                      (map flatten (cartesian-product context selector))
                      (map vector selector))]
    (for [sel new-context]
      (if-let [reference (extract-reference sel)]
        (let [parent (butlast sel)]
          (concat (butlast parent)
                  (list (u/as-str (last parent) reference))))
        sel))))

(defn- divide-rule
  "Divide a rule in to triple of selector, declarations, and subrules."
  [rule]
  (let [[selector children] (split-with (complement coll?) rule)]
    (loop [children children
           new-rule [selector [] []]]
      (if-let [child (first children)]
        (cond
         (map? child)
         (recur (next children) (update-in new-rule [1] conj child))
         (vector? child)
         (recur (next children) (update-in new-rule [2] conj child))
         (seq? child)
         (recur (apply concat child (rest children)) new-rule)
         :else
         (recur (next children) new-rule))
        new-rule))))

(defn- extract-media-query
  "Extracts media query information from rule meta data."
  [rule]
  (when-let [m (meta rule)]
    ;; The `:doc` key is reserved for CSS comments.
    (dissoc m :doc)))

(defn- ^String render-rule
  "Render a rule vector as a CSS rule."
  ([rule]
     (render-rule rule []))
  ([rule context]
     (if-let [mq (extract-media-query rule)]
       (do
         (add-media-query-rules! mq (u/without-meta rule) context)
         nil)
       (let [[selector declarations subrules] (divide-rule rule)
             new-context (expand-selector selector context)
             rendered-rule (when (every? seq [selector declarations])
                             (-> (u/comma-join new-context)
                                 (cons declarations)
                                 (vec)
                                 (make-rule)))]
         (if (seq subrules)
           (->> (map #(render-rule %1 new-context) subrules)
                (cons rendered-rule)
                (remove nil?)
                (s/join (u/rule-separator)))
           rendered-rule)))))

;;;; Media query generation.

(defn ^String make-media-expression
  "Make a media query expession from one or more maps. Keys are not
   validated but values have the following semantics:

   `true` as in `{:screen true}` means \"screen\"
   `false` as in `{:screen false}` means \"not screen\"
   \"only\" as in `{:screen \"only\"} means \"only screen\""
  ([expr]
   (let [query (for [[k v] expr]
                 (let [[sk sv] (map u/to-str [k v])]
                   (cond
                    (true? v) sk 
                    (false? v) (str "not " sk)
                    (= "only" sv) (str "only " sk)
                    :else (if (and v (seq sv))
                            (str "(" sk (u/colon) sv ")")
                            (str "(" sk ")")))))]
     (s/join " and " query)))
  ([expr & more]
   (u/comma-join (map make-media-expression (cons expr more)))))

(defn- ^String make-media-query
  "Make a CSS media query from one or more maps and a sequence of rules."
  ([expr rules] (make-media-query expr rules []))
  ([expr rules context]
     (let [expr (if (sequential? expr)
                  (apply make-media-expression expr)
                  (make-media-expression expr))
           rules  (let [rules (->> (map #(render-rule %1 context) rules)
                                   (s/join (u/rule-separator)))]
                    (if (= u/*output-style* :compressed)
                      rules
                      (s/replace rules #"(?m)(?=[ A-Za-z#.}-]+)^" (indent 2))))]
       (str "@media " expr (u/media-left-brace)
            rules
            (u/media-right-brace)))))

(extend-protocol CSSRenderer
  clojure.lang.IPersistentVector
  (render-css [this]
    (render-rule this))
  clojure.lang.IPersistentMap
  (render-css [this]
    (render-declaration this))
  clojure.lang.ISeq
  (render-css [this]
    (if-let [media-query (extract-media-query this)]
      (do
        (add-media-query-rules! media-query (u/without-meta this) nil)
        nil)
      (s/join (u/newline) (map render-css this))))
  clojure.lang.Ratio
  (render-css [this]
    (str (float this)))
  CSSUnit
  (render-css [this]
    (str this))
  garden.types.CSSFunction
  (render-css [this]
    (str this))
  Object
  (render-css [this]
    (str this))
  nil
  (render-css [this]
    ""))

(defn- ^String render-media-queries!
  "Compile media-queries."
  []
  (loop [rendered-queries []]
    (if (seq @media-query-rules)
      (let [[expr rules context] (first @media-query-rules)
            media-query (make-media-query expr rules context)]
        (reset! media-query-rules (vec (next @media-query-rules)))
        (recur (conj rendered-queries media-query)))
      (when (seq rendered-queries)
        (s/join (u/rule-separator) rendered-queries)))))

(defn ^String compile-css
  "Convert any number of Clojure data structures to CSS."
  [& rules]
  (let [top-level-rules (render-css rules)
        media-queries (render-media-queries!)]
    (if media-queries
      (str top-level-rules
           (when (seq top-level-rules)
             (u/rule-separator))
           media-queries)
      top-level-rules)))
