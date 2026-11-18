(ns examples.pi
  "Standalone executable that prints pi with a requested number of decimals.

  The digits are computed with the Rabinowitz-Wagon spigot algorithm, which
  produces one decimal digit per pass over a small integer array:

    n      = decimals + 2
    length = 10 * n / 3
    a[1..length] = 2
    nines = 0, predigit = 0
    repeat n times:
      q = 0
      for i = length down to 1:
        x = 10*a[i] + q*i
        a[i] = x mod (2*i - 1)
        q = x / (2*i - 1)
      a[1] = q mod 10
      q = q / 10
      if q = 9: nines += 1
      else if q = 10:
        emit predigit+1, then nines zeros
        predigit = 0, nines = 0
      else:
        emit predigit, then nines nines
        predigit = q, nines = 0
    emit predigit

  The first emitted digit is a leading zero, so the next one is the integer
  part. The algorithm generates one extra decimal beyond the requested
  precision, which is used to round the last printed digit.

  Build and run it with:

    clojure -M:dev -e \"(require 'clodin.compiler) (clodin.compiler/build-executable! 'examples.pi {:output \\\"pi\\\"})\"
    ./pi 10

  Expected output:

    3.1415926536"
  (:require [clodin.compiler :refer [defproc extern]]
            [clojure.test :refer [deftest is]]))

(extern printf [:ptr] :i32 :varargs)
(extern atoi [:ptr] :i32)
(extern malloc [:i64] :ptr)
(extern free [:ptr] :void)

(defproc print-pi [decimals :i32]
  (let [d :i64 (to-i64 decimals)
        n :i64 (+ d 2)
        length :i64 (/ (* 10 n) 3)
        a :ptr (malloc (* (+ length 1) 8))
        digits :ptr (malloc (* (+ n 2) 8))
        index :i64 1]
    ;; a[1..length] = 2
    (while (<= index length)
      (buffer-set a :i64 index 2)
      (set index (+ index 1)))
    (let [j :i64 1
          nines :i64 0
          predigit :i64 0
          output-index :i64 0]
      (while (<= j n)
        (let [q :i64 0
              i :i64 length]
          (while (>= i 1)
            (let [x :i64 (+ (* 10 (buffer-get a :i64 i)) (* q i))
                  divisor :i64 (- (* 2 i) 1)]
              (buffer-set a :i64 i (- x (* divisor (/ x divisor))))
              (set q (/ x divisor)))
            (set i (- i 1)))
          (buffer-set a :i64 1 (- q (* 10 (/ q 10))))
          (set q (/ q 10))
          (let [is-nine :i1 (= q 9)
                is-ten :i1 (= q 10)
                emit-head :i64 (if is-nine 0 1)
                head-value :i64 (if is-ten (+ predigit 1) predigit)
                tail-count :i64 (if is-nine 0 nines)
                tail-value :i64 (if is-ten 0 9)
                new-predigit :i64 (if is-nine predigit (if is-ten 0 q))
                new-nines :i64 (if is-nine (+ nines 1) 0)]
            (let [m :i64 0]
              (while (< m emit-head)
                (buffer-set digits :i64 output-index head-value)
                (set output-index (+ output-index 1))
                (set m (+ m 1))))
            (let [m :i64 0]
              (while (< m tail-count)
                (buffer-set digits :i64 output-index tail-value)
                (set output-index (+ output-index 1))
                (set m (+ m 1))))
            (set predigit new-predigit)
            (set nines new-nines)))
        (set j (+ j 1)))
      (buffer-set digits :i64 output-index predigit)
      (set output-index (+ output-index 1))
      ;; Round the last decimal using the extra generated digit.
      (let [last-index :i64 (- output-index 1)
            extra :i64 (buffer-get digits :i64 last-index)
            carry :i64 (if (>= extra 5) 1 0)
            carry-index :i64 (- last-index 1)]
        (while (> carry 0)
          (let [value :i64 (+ (buffer-get digits :i64 carry-index) carry)
                overflow :i1 (>= value 10)
                next-carry :i64 (if overflow 1 0)
                stored-value :i64 (if overflow (- value 10) value)]
            (buffer-set digits :i64 carry-index stored-value)
            (set carry next-carry))
          (set carry-index (- carry-index 1))
          (set carry (* carry (if (>= carry-index 1) 1 0)))))
      (printf "%ld" (buffer-get digits :i64 1))
      (let [point-index :i64 0
            point-end :i64 (if (> d 0) 1 0)]
        (while (< point-index point-end)
          (printf ".")
          (set point-index (+ point-index 1))))
      (let [digit-index :i64 2
            digit-end :i64 (+ d 1)]
        (while (<= digit-index digit-end)
          (printf "%ld" (buffer-get digits :i64 digit-index))
          (set digit-index (+ digit-index 1))))
      (printf "\n")
      (free a)
      (free digits))))

(defproc main [argc :i32 argv :ptr]
  :i32
  (print-pi (atoi (load :ptr (pointer-add argv 8))))
  (to-i32 0))

(deftest test-print-pi
  (is (= "3.1415926536\n" (with-out-str (print-pi 10)))))

(comment
  (clodin.compiler/build-executable! *ns* {:output "pi"})

  (time (print-pi 5000))
  )
