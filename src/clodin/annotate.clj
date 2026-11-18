(ns clodin.annotate
  "Annotates AArch64 assembly with plain-English descriptions.

  (print-annotated-assembly! (clodin.inspect/module-assembly 'my.namespace))

  The annotator is intentionally a text tool: it takes the string returned by
  clodin.inspect/assembly or clodin.inspect/module-assembly and appends a
  // comment to each instruction, directive and label line. Unknown
  instructions still get their operands described."
  (:require [clojure.string :as string]))

;;; ---------------------------------------------------------------------------
;;; Line and operand parsing
;;; ---------------------------------------------------------------------------

(defn- split-comment
  [line]
  (let [index (string/index-of line "//")]
    (if index
      [(string/trimr (subs line 0 index)) (subs line index)]
      [line nil])))

(defn- split-operands
  "Splits on commas that are not nested inside [], {} or ()."
  [text]
  (loop [characters (seq text)
         current []
         depth 0
         operands []]
    (if-let [character (first characters)]
      (cond
        (#{\[ \{ \(} character)
        (recur (rest characters) (conj current character) (inc depth) operands)

        (#{\] \} \)} character)
        (recur (rest characters) (conj current character) (dec depth) operands)

        (and (= \, character) (zero? depth))
        (recur (rest characters) [] depth
               (conj operands (string/trim (apply str current))))

        :else
        (recur (rest characters) (conj current character) depth operands))
      (let [operand (string/trim (apply str current))]
        (if (empty? operand)
          operands
          (conj operands operand))))))

(defn- parse-instruction
  [text]
  (let [[_ mnemonic operand-text] (re-find #"^(\S+)\s*(.*)$" text)]
    [mnemonic (if (empty? operand-text) [] (split-operands operand-text))]))

;;; ---------------------------------------------------------------------------
;;; Operand descriptions
;;; ---------------------------------------------------------------------------

(defn- vector-lane-description
  [suffix]
  (case suffix
    "16b" "16 x i8"
    "8b" "8 x i8"
    "8h" "8 x i16"
    "4h" "4 x i16"
    "4s" "4 x f32"
    "2s" "2 x f32"
    "2d" "2 x f64"
    "1d" "1 x f64"
    "1s" "1 x f32"
    suffix))

(defn- describe-register
  [operand]
  (cond
    (re-matches #"v\d+\.(16b|8b|8h|4h|4s|2s|2d|1d|1s)-v\d+\.(16b|8b|8h|4h|4s|2s|2d|1d|1s)" operand)
    (let [[_ first-register suffix second-register _second-suffix]
          (re-matches #"(v\d+)\.(16b|8b|8h|4h|4s|2s|2d|1d|1s)-(v\d+)\.(16b|8b|8h|4h|4s|2s|2d|1d|1s)" operand)]
      (str first-register " (" (vector-lane-description suffix) ") to "
           second-register " (" (vector-lane-description suffix) ")"))

    (re-matches #"v\d+\.(16b|8b|8h|4h|4s|2s|2d|1d|1s)" operand)
    (let [[_ register suffix]
          (re-matches #"(v\d+)\.(16b|8b|8h|4h|4s|2s|2d|1d|1s)" operand)]
      (str register " (" (vector-lane-description suffix) ")"))

    (re-matches #"v\d+\[\d+\]" operand)
    (let [[_ register lane] (re-matches #"(v\d+)\[(\d+)\]" operand)]
      (str "lane " lane " of " register))

    (re-matches #"[xw]\d+" operand) operand
    (re-matches #"s\d+" operand) (str operand " (f32)")
    (re-matches #"d\d+" operand) (str operand " (f64)")
    (re-matches #"q\d+" operand) (str operand " (128-bit)")
    (re-matches #"h\d+" operand) (str operand " (f16)")
    (re-matches #"b\d+" operand) (str operand " (8-bit)")
    (= operand "sp") "sp"
    (= operand "xzr") "zero register xzr"
    (= operand "wzr") "zero register wzr"
    (= operand "lr") "link register x30"
    (= operand "fp") "frame pointer x29"
    :else operand))

(defn- describe-operand
  [operand]
  (cond
    (string/starts-with? operand "#") (str "immediate " operand)
    (string/starts-with? operand "\"") (str "symbol " operand)
    (string/starts-with? operand "[")
    (let [pre-index? (string/ends-with? operand "!")
          inner (subs operand 1 (- (count operand) (if pre-index? 2 1)))]
      (str "memory at "
           (string/join " + " (map describe-operand (split-operands inner)))
           (when pre-index? " (pre-index)")))
    (string/starts-with? operand "{")
    (str "{" (string/join ", "
                           (map describe-operand
                                (split-operands (subs operand 1 (dec (count operand))))))
         "}")
    :else (describe-register operand)))

;;; ---------------------------------------------------------------------------
;;; Instruction descriptions
;;; ---------------------------------------------------------------------------

(def ^:private condition-descriptions
  {"eq" "equal (Z=1)"
   "ne" "not equal (Z=0)"
   "cs" "carry set (C=1)"
   "hs" "unsigned higher or same (C=1)"
   "cc" "carry clear (C=0)"
   "lo" "unsigned lower (C=0)"
   "mi" "negative (N=1)"
   "pl" "positive (N=0)"
   "vs" "overflow set (V=1)"
   "vc" "overflow clear (V=0)"
   "hi" "unsigned higher (C=1 and Z=0)"
   "ls" "unsigned lower or same (C=0 or Z=1)"
   "ge" "signed greater or equal (N=V)"
   "lt" "signed less than (N!=V)"
   "gt" "signed greater than (Z=0 and N=V)"
   "le" "signed less or equal (Z=1 or N!=V)"
   "al" "always"})

(defn- condition-description
  [condition]
  (get condition-descriptions condition condition))

(defn- immediate-value
  [operand]
  (if (string/starts-with? operand "#")
    (subs operand 1)
    operand))

(defn- shift-suffix
  [extra-operands]
  (when (seq extra-operands)
    (str " " (string/join " " extra-operands))))

(defn- post-index-suffix
  [extra-operands]
  (when (seq extra-operands)
    (let [operand (first extra-operands)]
      (if (string/starts-with? operand "#")
        (str " (then advance the address by " (subs operand 1) " bytes)")
        (str " (then advance the address by " operand ")")))))

(def ^:private instruction-descriptions
  {"mov" (fn [[dst src]] (str (describe-operand dst) " = " (describe-operand src)))
  "movz" (fn [[dst src]] (str (describe-operand dst) " = zero-extended " (describe-operand src)))
  "movk" (fn [[dst src]] (str "insert " (describe-operand src) " into " (describe-operand dst)))
  "mvn" (fn [[dst src]] (str (describe-operand dst) " = ~" (describe-operand src)))
  "add" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " + " (describe-operand b) (shift-suffix extra)))
  "adds" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " + " (describe-operand b) (shift-suffix extra) " (sets flags)"))
  "sub" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " - " (describe-operand b) (shift-suffix extra)))
  "subs" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " - " (describe-operand b) (shift-suffix extra) " (sets flags)"))
  "mul" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " * " (describe-operand b) (shift-suffix extra)))
  "madd" (fn [[dst a b c & extra]] (str (describe-operand dst) " = " (describe-operand a) " * " (describe-operand b) " + " (describe-operand c) (shift-suffix extra)))
  "msub" (fn [[dst a b c & extra]] (str (describe-operand dst) " = " (describe-operand c) " - " (describe-operand a) " * " (describe-operand b) (shift-suffix extra)))
  "sdiv" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " / " (describe-operand b) " (signed)" (shift-suffix extra)))
  "udiv" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " / " (describe-operand b) " (unsigned)" (shift-suffix extra)))
  "neg" (fn [[dst src & extra]] (str (describe-operand dst) " = -" (describe-operand src) (shift-suffix extra)))
  "negs" (fn [[dst src & extra]] (str (describe-operand dst) " = -" (describe-operand src) (shift-suffix extra) " (sets flags)"))
  "and" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " & " (describe-operand b) (shift-suffix extra)))
  "ands" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " & " (describe-operand b) (shift-suffix extra) " (sets flags)"))
  "orr" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " | " (describe-operand b) (shift-suffix extra)))
  "eor" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " ^ " (describe-operand b) (shift-suffix extra)))
  "bic" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " & ~" (describe-operand b) (shift-suffix extra)))
  "lsl" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " << " (describe-operand b) (shift-suffix extra)))
  "lsr" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " >> " (describe-operand b) " (logical)" (shift-suffix extra)))
  "asr" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " >> " (describe-operand b) " (arithmetic)" (shift-suffix extra)))
  "ror" (fn [[dst a b & extra]] (str (describe-operand dst) " = rotate-right " (describe-operand a) " by " (describe-operand b) (shift-suffix extra)))
  "cmp" (fn [[a b & extra]] (str "compare " (describe-operand a) " with " (describe-operand b) (shift-suffix extra) " (sets flags)"))
  "cmn" (fn [[a b & extra]] (str "compare negative " (describe-operand a) " + " (describe-operand b) (shift-suffix extra) " (sets flags)"))
  "tst" (fn [[a b & extra]] (str "test " (describe-operand a) " & " (describe-operand b) (shift-suffix extra) " (sets flags)"))
  "fcmp" (fn [[a b & extra]] (str "compare floats " (describe-operand a) " and " (describe-operand b) (shift-suffix extra) " (sets flags)"))
  "fcmpe" (fn [[a b & extra]] (str "compare floats " (describe-operand a) " and " (describe-operand b) (shift-suffix extra) " (sets flags, raises on NaN)"))
  "ccmp" (fn [[a b nzcv condition]]
          (str "if " (condition-description condition)
               ", compare " (describe-operand a) " with " (describe-operand b)
               "; otherwise set flags to " (immediate-value nzcv)))
  "ccmn" (fn [[a b nzcv condition]]
          (str "if " (condition-description condition)
               ", compare negative " (describe-operand a) " + " (describe-operand b)
               "; otherwise set flags to " (immediate-value nzcv)))
  "fccmp" (fn [[a b nzcv condition]]
           (str "if " (condition-description condition)
                ", compare floats " (describe-operand a) " and " (describe-operand b)
                "; otherwise set flags to " (immediate-value nzcv)))
  "fccmpe" (fn [[a b nzcv condition]]
            (str "if " (condition-description condition)
                 ", compare floats " (describe-operand a) " and " (describe-operand b)
                 "; otherwise set flags to " (immediate-value nzcv)
                 " (raises on NaN)"))
  "b" (fn [[target]] (str "branch to " (describe-operand target)))
  "bl" (fn [[target]] (str "call " (describe-operand target) ", link return address in x30"))
  "br" (fn [[target]] (str "branch to address in " (describe-operand target)))
  "blr" (fn [[target]] (str "call address in " (describe-operand target) ", link return address in x30"))
  "ret" (fn [_] "return to caller (address in x30)")
  "cbz" (fn [[register target]] (str "branch to " (describe-operand target) " if " (describe-operand register) " is zero"))
  "cbnz" (fn [[register target]] (str "branch to " (describe-operand target) " if " (describe-operand register) " is non-zero"))
  "tbz" (fn [[register bit target]] (str "branch to " (describe-operand target) " if bit " (describe-operand bit) " of " (describe-operand register) " is zero"))
  "tbnz" (fn [[register bit target]] (str "branch to " (describe-operand target) " if bit " (describe-operand bit) " of " (describe-operand register) " is non-zero"))
  "adrp" (fn [[dst target]] (str (describe-operand dst) " = page address of " (describe-operand target)))
  "adr" (fn [[dst target]] (str (describe-operand dst) " = address of " (describe-operand target)))
  "ldr" (fn [[dst address & extra]] (str "load " (describe-operand dst) " from " (describe-operand address) (post-index-suffix extra)))
  "ldrb" (fn [[dst address & extra]] (str "load byte into " (describe-operand dst) " from " (describe-operand address) (post-index-suffix extra)))
  "ldrh" (fn [[dst address & extra]] (str "load halfword into " (describe-operand dst) " from " (describe-operand address) (post-index-suffix extra)))
  "ldrsb" (fn [[dst address & extra]] (str "load signed byte into " (describe-operand dst) " from " (describe-operand address) (post-index-suffix extra)))
  "ldrsh" (fn [[dst address & extra]] (str "load signed halfword into " (describe-operand dst) " from " (describe-operand address) (post-index-suffix extra)))
  "ldrsw" (fn [[dst address & extra]] (str "load signed word into " (describe-operand dst) " from " (describe-operand address) (post-index-suffix extra)))
  "str" (fn [[src address & extra]] (str "store " (describe-operand src) " to " (describe-operand address) (post-index-suffix extra)))
  "strb" (fn [[src address & extra]] (str "store byte " (describe-operand src) " to " (describe-operand address) (post-index-suffix extra)))
  "strh" (fn [[src address & extra]] (str "store halfword " (describe-operand src) " to " (describe-operand address) (post-index-suffix extra)))
  "ldp" (fn [[dst1 dst2 address & extra]] (str "load pair " (describe-operand dst1) ", " (describe-operand dst2) " from " (describe-operand address) (post-index-suffix extra)))
  "stp" (fn [[src1 src2 address & extra]] (str "store pair " (describe-operand src1) ", " (describe-operand src2) " to " (describe-operand address) (post-index-suffix extra)))
  "ld1" (fn [[registers address & extra]] (str "load 1 vector " (describe-operand registers) " from " (describe-operand address) (post-index-suffix extra)))
  "ld2" (fn [[registers address & extra]] (str "load 2 interleaved vectors " (describe-operand registers) " from " (describe-operand address) (post-index-suffix extra)))
  "ld3" (fn [[registers address & extra]] (str "load 3 interleaved vectors " (describe-operand registers) " from " (describe-operand address) (post-index-suffix extra)))
  "ld4" (fn [[registers address & extra]] (str "load 4 interleaved vectors " (describe-operand registers) " from " (describe-operand address) (post-index-suffix extra)))
  "st1" (fn [[registers address & extra]] (str "store 1 vector " (describe-operand registers) " to " (describe-operand address) (post-index-suffix extra)))
  "st2" (fn [[registers address & extra]] (str "store 2 interleaved vectors " (describe-operand registers) " to " (describe-operand address) (post-index-suffix extra)))
  "st3" (fn [[registers address & extra]] (str "store 3 interleaved vectors " (describe-operand registers) " to " (describe-operand address) (post-index-suffix extra)))
  "st4" (fn [[registers address & extra]] (str "store 4 interleaved vectors " (describe-operand registers) " to " (describe-operand address) (post-index-suffix extra)))
  "fmov" (fn [[dst src]] (str (describe-operand dst) " = " (describe-operand src) " (float move)"))
  "fadd" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " + " (describe-operand b) " (float)" (shift-suffix extra)))
  "fsub" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " - " (describe-operand b) " (float)" (shift-suffix extra)))
  "fmul" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " * " (describe-operand b) " (float)" (shift-suffix extra)))
  "fdiv" (fn [[dst a b & extra]] (str (describe-operand dst) " = " (describe-operand a) " / " (describe-operand b) " (float)" (shift-suffix extra)))
  "fmax" (fn [[dst a b & extra]] (str (describe-operand dst) " = max(" (describe-operand a) ", " (describe-operand b) ")" (shift-suffix extra)))
  "fmin" (fn [[dst a b & extra]] (str (describe-operand dst) " = min(" (describe-operand a) ", " (describe-operand b) ")" (shift-suffix extra)))
  "fmaxnm" (fn [[dst a b & extra]] (str (describe-operand dst) " = max(" (describe-operand a) ", " (describe-operand b) ") (NaN -> number)" (shift-suffix extra)))
  "fminnm" (fn [[dst a b & extra]] (str (describe-operand dst) " = min(" (describe-operand a) ", " (describe-operand b) ") (NaN -> number)" (shift-suffix extra)))
  "fcmgt" (fn [[dst a b]] (str (describe-operand dst) " = lanes of " (describe-operand a) " > " (describe-operand b) " (float compare, all-ones/all-zeros)"))
  "fcmge" (fn [[dst a b]] (str (describe-operand dst) " = lanes of " (describe-operand a) " >= " (describe-operand b) " (float compare, all-ones/all-zeros)"))
  "fcmeq" (fn [[dst a b]] (str (describe-operand dst) " = lanes of " (describe-operand a) " == " (describe-operand b) " (float compare, all-ones/all-zeros)"))
  "fcmlt" (fn [[dst a b]] (str (describe-operand dst) " = lanes of " (describe-operand a) " < " (describe-operand b) " (float compare, all-ones/all-zeros)"))
  "fcmle" (fn [[dst a b]] (str (describe-operand dst) " = lanes of " (describe-operand a) " <= " (describe-operand b) " (float compare, all-ones/all-zeros)"))
  "fcmne" (fn [[dst a b]] (str (describe-operand dst) " = lanes of " (describe-operand a) " != " (describe-operand b) " (float compare, all-ones/all-zeros)"))
  "faddp" (fn [[dst src]] (str (describe-operand dst) " = horizontal add of the lanes of " (describe-operand src)))
  "fmaxp" (fn [[dst src]] (str (describe-operand dst) " = horizontal max of the lanes of " (describe-operand src)))
  "fminp" (fn [[dst src]] (str (describe-operand dst) " = horizontal min of the lanes of " (describe-operand src)))
  "fmaxnmp" (fn [[dst src]] (str (describe-operand dst) " = horizontal max of the lanes of " (describe-operand src) " (NaN -> number)"))
  "fminnmp" (fn [[dst src]] (str (describe-operand dst) " = horizontal min of the lanes of " (describe-operand src) " (NaN -> number)"))
  "addp" (fn [[dst src]] (str (describe-operand dst) " = horizontal add of the lanes of " (describe-operand src)))
  "shl" (fn [[dst src amount]] (str (describe-operand dst) " = " (describe-operand src) " << " (describe-operand amount) " (per lane)"))
  "sshr" (fn [[dst src amount]] (str (describe-operand dst) " = " (describe-operand src) " >> " (describe-operand amount) " (signed, per lane)"))
  "ushr" (fn [[dst src amount]] (str (describe-operand dst) " = " (describe-operand src) " >> " (describe-operand amount) " (unsigned, per lane)"))
  "sli" (fn [[dst src amount]] (str "shift " (describe-operand src) " left by " (describe-operand amount) " and insert into " (describe-operand dst)))
  "sri" (fn [[dst src amount]] (str "shift " (describe-operand src) " right by " (describe-operand amount) " and insert into " (describe-operand dst)))
  "shrn" (fn [[dst src amount]] (str (describe-operand dst) " = narrow " (describe-operand src) " >> " (describe-operand amount)))
  "sshll" (fn [[dst src amount]] (str (describe-operand dst) " = sign-extend and shift left " (describe-operand src) " by " (describe-operand amount)))
  "ushll" (fn [[dst src amount]] (str (describe-operand dst) " = zero-extend and shift left " (describe-operand src) " by " (describe-operand amount)))
  "sxtl" (fn [[dst src]] (str (describe-operand dst) " = sign-extend lanes of " (describe-operand src)))
  "uxtl" (fn [[dst src]] (str (describe-operand dst) " = zero-extend lanes of " (describe-operand src)))
  "fmla" (fn [[dst a b]] (str (describe-operand dst) " += " (describe-operand a) " * " (describe-operand b) " (float, fused)"))
  "fmls" (fn [[dst a b]] (str (describe-operand dst) " -= " (describe-operand a) " * " (describe-operand b) " (float, fused)"))
  "mla" (fn [[dst a b]] (str (describe-operand dst) " += " (describe-operand a) " * " (describe-operand b)))
  "mls" (fn [[dst a b]] (str (describe-operand dst) " -= " (describe-operand a) " * " (describe-operand b)))
  "addv" (fn [[dst src]] (str (describe-operand dst) " = sum of the lanes of " (describe-operand src)))
  "saddv" (fn [[dst src]] (str (describe-operand dst) " = signed sum of the lanes of " (describe-operand src)))
  "uaddv" (fn [[dst src]] (str (describe-operand dst) " = unsigned sum of the lanes of " (describe-operand src)))
  "smaxv" (fn [[dst src]] (str (describe-operand dst) " = signed max of the lanes of " (describe-operand src)))
  "umaxv" (fn [[dst src]] (str (describe-operand dst) " = unsigned max of the lanes of " (describe-operand src)))
  "sminv" (fn [[dst src]] (str (describe-operand dst) " = signed min of the lanes of " (describe-operand src)))
  "uminv" (fn [[dst src]] (str (describe-operand dst) " = unsigned min of the lanes of " (describe-operand src)))
  "not" (fn [[dst src]] (str (describe-operand dst) " = ~" (describe-operand src) " (per lane)"))
  "mvni" (fn [[dst src]] (str "set all lanes of " (describe-operand dst) " to ~" (describe-operand src)))
  "abs" (fn [[dst src]] (str (describe-operand dst) " = abs(" (describe-operand src) ") (per lane)"))
  "sqabs" (fn [[dst src]] (str (describe-operand dst) " = saturating abs(" (describe-operand src) ")"))
  "frecpe" (fn [[dst src]] (str (describe-operand dst) " = approximate reciprocal of " (describe-operand src)))
  "frsqrte" (fn [[dst src]] (str (describe-operand dst) " = approximate reciprocal square root of " (describe-operand src)))
  "frecps" (fn [[dst a b]] (str (describe-operand dst) " = 2 - " (describe-operand a) " * " (describe-operand b) " (reciprocal step)"))
  "frsqrts" (fn [[dst a b]] (str (describe-operand dst) " = (3 - " (describe-operand a) " * " (describe-operand b) ") / 2 (reciprocal sqrt step)"))
  "frinta" (fn [[dst src]] (str (describe-operand dst) " = round " (describe-operand src) " to nearest, ties away from zero"))
  "frintm" (fn [[dst src]] (str (describe-operand dst) " = round " (describe-operand src) " toward -infinity"))
  "frintn" (fn [[dst src]] (str (describe-operand dst) " = round " (describe-operand src) " to nearest, ties to even"))
  "frintp" (fn [[dst src]] (str (describe-operand dst) " = round " (describe-operand src) " toward +infinity"))
  "frintz" (fn [[dst src]] (str (describe-operand dst) " = round " (describe-operand src) " toward zero"))
  "frintx" (fn [[dst src]] (str (describe-operand dst) " = round " (describe-operand src) " using the current rounding mode"))
  "frinti" (fn [[dst src]] (str (describe-operand dst) " = round " (describe-operand src) " using the current rounding mode (exact)"))
  "fmadd" (fn [[dst a b c & extra]] (str (describe-operand dst) " = " (describe-operand a) " * " (describe-operand b) " + " (describe-operand c) " (float)" (shift-suffix extra)))
  "fmsub" (fn [[dst a b c & extra]] (str (describe-operand dst) " = " (describe-operand c) " - " (describe-operand a) " * " (describe-operand b) " (float)" (shift-suffix extra)))
  "fnmadd" (fn [[dst a b c & extra]] (str (describe-operand dst) " = -(" (describe-operand a) " * " (describe-operand b) ") + " (describe-operand c) " (float)" (shift-suffix extra)))
  "fnmsub" (fn [[dst a b c & extra]] (str (describe-operand dst) " = -(" (describe-operand a) " * " (describe-operand b) ") - " (describe-operand c) " (float)" (shift-suffix extra)))
  "fsqrt" (fn [[dst src & extra]] (str (describe-operand dst) " = sqrt(" (describe-operand src) ")" (shift-suffix extra)))
  "fabs" (fn [[dst src & extra]] (str (describe-operand dst) " = abs(" (describe-operand src) ")" (shift-suffix extra)))
  "fneg" (fn [[dst src & extra]] (str (describe-operand dst) " = -" (describe-operand src) (shift-suffix extra)))
  "scvtf" (fn [[dst src & extra]] (str (describe-operand dst) " = float(" (describe-operand src) ") (signed)" (shift-suffix extra)))
  "ucvtf" (fn [[dst src & extra]] (str (describe-operand dst) " = float(" (describe-operand src) ") (unsigned)" (shift-suffix extra)))
  "fcvtzs" (fn [[dst src & extra]] (str (describe-operand dst) " = integer(" (describe-operand src) ") (signed, truncate)" (shift-suffix extra)))
  "fcvtzu" (fn [[dst src & extra]] (str (describe-operand dst) " = integer(" (describe-operand src) ") (unsigned, truncate)" (shift-suffix extra)))
  "dup" (fn [[dst src]] (str "broadcast " (describe-operand src) " into all lanes of " (describe-operand dst)))
  "ins" (fn [[dst src]] (str "insert " (describe-operand src) " into " (describe-operand dst)))
  "movi" (fn [[dst src]] (str "set all lanes of " (describe-operand dst) " to " (describe-operand src)))
  "bsl" (fn [[dst a b]] (str (describe-operand dst) " = bitwise select(" (describe-operand a) ", " (describe-operand b) ")"))
  "bit" (fn [[dst a b]] (str "insert bits from " (describe-operand a) " into " (describe-operand dst) " where " (describe-operand b) " is set"))
  "bif" (fn [[dst a b]] (str "insert bits from " (describe-operand a) " into " (describe-operand dst) " where " (describe-operand b) " is clear"))
  "cmeq" (fn [[dst a b]] (str (describe-operand dst) " = lanes of " (describe-operand a) " equal to " (describe-operand b)))
  "cmge" (fn [[dst a b]] (str (describe-operand dst) " = lanes of " (describe-operand a) " >= " (describe-operand b)))
  "cmgt" (fn [[dst a b]] (str (describe-operand dst) " = lanes of " (describe-operand a) " > " (describe-operand b)))
  "cmle" (fn [[dst a b]] (str (describe-operand dst) " = lanes of " (describe-operand a) " <= " (describe-operand b)))
  "cmlt" (fn [[dst a b]] (str (describe-operand dst) " = lanes of " (describe-operand a) " < " (describe-operand b)))
  "zip1" (fn [[dst a b]] (str "interleave lower lanes of " (describe-operand a) " and " (describe-operand b) " into " (describe-operand dst)))
  "zip2" (fn [[dst a b]] (str "interleave upper lanes of " (describe-operand a) " and " (describe-operand b) " into " (describe-operand dst)))
  "uzp1" (fn [[dst a b]] (str "deinterleave even lanes of " (describe-operand a) " and " (describe-operand b) " into " (describe-operand dst)))
  "uzp2" (fn [[dst a b]] (str "deinterleave odd lanes of " (describe-operand a) " and " (describe-operand b) " into " (describe-operand dst)))
  "trn1" (fn [[dst a b]] (str "transpose lower lanes of " (describe-operand a) " and " (describe-operand b) " into " (describe-operand dst)))
  "trn2" (fn [[dst a b]] (str "transpose upper lanes of " (describe-operand a) " and " (describe-operand b) " into " (describe-operand dst)))
  "ext" (fn [[dst a b index]] (str (describe-operand dst) " = extract lanes from " (describe-operand a) " and " (describe-operand b) " starting at " (describe-operand index)))
  "sxtb" (fn [[dst src]] (str (describe-operand dst) " = sign-extend byte " (describe-operand src)))
  "sxth" (fn [[dst src]] (str (describe-operand dst) " = sign-extend halfword " (describe-operand src)))
  "sxtw" (fn [[dst src]] (str (describe-operand dst) " = sign-extend word " (describe-operand src)))
  "uxtb" (fn [[dst src]] (str (describe-operand dst) " = zero-extend byte " (describe-operand src)))
  "uxth" (fn [[dst src]] (str (describe-operand dst) " = zero-extend halfword " (describe-operand src)))
  "uxtw" (fn [[dst src]] (str (describe-operand dst) " = zero-extend word " (describe-operand src)))
  "csel" (fn [[dst a b condition]] (str (describe-operand dst) " = " (describe-operand a) " if " (condition-description condition) ", else " (describe-operand b)))
  "csinc" (fn [[dst a b condition]] (str (describe-operand dst) " = " (describe-operand a) " if " (condition-description condition) ", else " (describe-operand b) " + 1"))
  "csinv" (fn [[dst a b condition]] (str (describe-operand dst) " = " (describe-operand a) " if " (condition-description condition) ", else ~" (describe-operand b)))
  "csneg" (fn [[dst a b condition]] (str (describe-operand dst) " = " (describe-operand a) " if " (condition-description condition) ", else -" (describe-operand b)))
  "cset" (fn [[dst condition]] (str (describe-operand dst) " = 1 if " (condition-description condition) ", else 0"))
  "csetm" (fn [[dst condition]] (str (describe-operand dst) " = all-ones if " (condition-description condition) ", else 0"))
  "nop" (fn [_] "no operation")
  "dmb" (fn [options] (str "data memory barrier " (string/join ", " options)))
  "dsb" (fn [options] (str "data synchronization barrier " (string/join ", " options)))
  "isb" (fn [options] (str "instruction synchronization barrier " (string/join ", " options)))
  "svc" (fn [[immediate]] (str "supervisor call " (describe-operand immediate)))
  "brk" (fn [[immediate]] (str "breakpoint " (describe-operand immediate)))
  "mrs" (fn [[dst system-register]] (str (describe-operand dst) " = read system register " (describe-operand system-register)))
  "msr" (fn [[system-register src]] (str "write " (describe-operand src) " to system register " (describe-operand system-register)))
  "prfm" (fn [[operation address]] (str "prefetch " (describe-operand operation) " from " (describe-operand address)))})

(defn- split-mnemonic-suffix
  "Separates a mnemonic such as st2.4s or fadd.4s into its base mnemonic and
  element suffix. Branch conditions such as b.eq do not match the suffix set,
  so they are left intact."
  [mnemonic]
  (if-let [[_ base suffix]
           (re-matches #"([a-z][a-z0-9]*?)\.(16b|8b|8h|4h|4s|2s|2d|1d|1s|[sdhb])" mnemonic)]
    [base suffix]
    [mnemonic nil]))

(defn- apply-element-type
  "Adds the element suffix to bare vector registers in a structure operand,
  so st2.4s { v4, v5 } describes v4 and v5 as 4 x f32."
  [operand suffix]
  (cond
    (and suffix (re-matches #"v\d+" operand))
    (str operand "." suffix)

    (and suffix (string/starts-with? operand "{"))
    (str "{" (string/join ", "
                           (map #(apply-element-type % suffix)
                                (split-operands (subs operand 1 (dec (count operand))))))
         "}")

    :else operand))

(defn- describe-instruction
  [mnemonic operands]
  (let [lower (string/lower-case mnemonic)
        [base suffix] (split-mnemonic-suffix lower)
        operands (if suffix
                   (mapv #(apply-element-type % suffix) operands)
                   operands)]
    (cond
      (string/starts-with? lower "b.")
      (str "branch to " (describe-operand (first operands))
           " if " (condition-description (subs lower 2)))

      :else
      (if-let [describe (get instruction-descriptions base)]
        (describe operands)
        (str "operation " lower " "
             (string/join ", " (map describe-operand operands)))))))

;;; ---------------------------------------------------------------------------
;;; Directives and labels
;;; ---------------------------------------------------------------------------

(defn- describe-directive
  [directive]
  (let [[name & args] (string/split directive #"\s+" 2)
        arguments (string/join " " args)]
    (case name
      ".file" (str "source file " arguments)
      ".text" "code section"
      ".data" "data section"
      ".rodata" "read-only data section"
      ".section" (str "section " arguments)
      ".globl" (str "export symbol " arguments)
      ".global" (str "export symbol " arguments)
      ".p2align" (let [[power fill] (string/split arguments #",\s*")]
                   (str "align to 2^" power " bytes"
                        (when fill (str ", fill with " fill))))
      ".align" (str "align to " arguments)
      ".type" (str "declare symbol type: " arguments)
      ".size" (str "declare symbol size: " arguments)
      ".asciz" (str "null-terminated string " arguments)
      ".string" (str "null-terminated string " arguments)
      ".ascii" (str "string " arguments)
      ".byte" (str "byte values " arguments)
      ".short" (str "16-bit values " arguments)
      ".word" (str "32-bit values " arguments)
      ".quad" (str "64-bit values " arguments)
      ".zero" (str "zero-filled bytes " arguments)
      ".space" (str "reserve bytes " arguments)
      ".cfi_startproc" "start call frame information"
      ".cfi_endproc" "end call frame information"
      ".cfi_def_cfa_offset" (str "call frame offset " arguments)
      ".cfi_offset" (str "call frame register offset " arguments)
      ".note.GNU-stack" "mark stack non-executable"
      (str "directive " name " " arguments))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn- annotate-line
  [line]
  (let [[body _comment] (split-comment line)
        trimmed (string/trim body)]
    (cond
      (empty? trimmed) line
      (string/ends-with? trimmed ":")
      (str line "  // label " (string/trimr (subs trimmed 0 (dec (count trimmed)))))
      (string/starts-with? trimmed ".")
      (str line "  // " (describe-directive trimmed))
      :else
      (let [[mnemonic operands] (parse-instruction trimmed)]
        (str line "  // " (describe-instruction mnemonic operands))))))

(defn annotate-assembly
  "Returns assembly with a plain-English // comment appended to each
  instruction, directive and label line."
  [assembly]
  (string/join "\n" (map annotate-line (string/split-lines assembly))))

(defn print-annotated-assembly!
  "Prints assembly with a plain-English description on each line."
  [assembly]
  (println (annotate-assembly assembly)))

;;; ---------------------------------------------------------------------------
;;; Disassembly annotation
;;; ---------------------------------------------------------------------------

(defn- disassembly-instruction
  "Extracts the instruction text from an objdump or otool disassembly line.
  Handles both tab-separated Linux output and space-separated macOS output."
  [line]
  (let [line (-> line
                 (string/replace "\t" " ")
                 (string/trim))]
    (cond
      ;; objdump: "0: f100045f         cmp    x2, #0x1"
      ;; objdump: "0: 8b000020  add x0, x1, x0"
      (re-matches #"^[0-9a-f]+:.*" line)
      (let [parts (string/split line #"\s{2,}")]
        (when (>= (count parts) 2)
          (-> (string/join " " (rest parts))
              (string/split #";")
              first
              string/trim)))

      ;; otool: "0000000000000000 add x0, x1, x0"
      (re-matches #"^[0-9a-f]+\s+.*" line)
      (-> (second (re-matches #"^[0-9a-f]+\s+(.*)$" line))
          (string/split #";")
          first
          string/trim)

      :else nil)))

(defn- annotate-disassembly-line
  [line]
  (cond
    (string/blank? line) line

    (re-matches #"^\s*[0-9a-f]+ <([^>]+)>:$" line)
    (let [[_ symbol] (re-matches #"^\s*[0-9a-f]+ <([^>]+)>:$" line)]
      (str line "  // function " symbol))

    (re-matches #"^\s*_?[A-Za-z_.][A-Za-z0-9_.$-]*:$" line)
    (str line "  // label " (string/trimr (string/trim line) ":"))

    (re-matches #"^Disassembly of section (.*):$" line)
    (str line "  // section " (second (re-matches #"^Disassembly of section (.*):$" line)))

    (re-matches #"^\(([^)]+)\) section$" line)
    (str line "  // section " (second (re-matches #"^\(([^)]+)\) section$" line)))

    :else
    (if-let [instruction (disassembly-instruction line)]
      (let [[mnemonic operands] (parse-instruction (string/trim instruction))]
        (str line "  // " (describe-instruction mnemonic operands)))
      line)))

(defn annotate-disassembly
  "Returns disassembly with a plain-English // comment appended to each
  instruction and label line. Handles objdump (Linux) and otool (macOS)
  output."
  [disassembly]
  (string/join "\n" (map annotate-disassembly-line (string/split-lines disassembly))))

(defn print-annotated-disassembly!
  "Prints disassembly with a plain-English description on each line."
  [disassembly]
  (println (annotate-disassembly disassembly)))
