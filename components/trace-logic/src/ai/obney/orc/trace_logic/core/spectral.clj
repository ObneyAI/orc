(ns ai.obney.orc.trace-logic.core.spectral
  "Spectral analysis of Markov kernels.

   Eigen-decomposition gives the modal structure of the chain:
   - The principal eigenvalue is 1 (Markov property); its left
     eigenvector is the stationary measure.
   - The second-largest eigenvalue magnitude (λ₂) controls the
     convergence rate to the stationary measure (paper §6, §7;
     also Hoffman lecture).
   - Eigenvectors with eigenvalues near 1 reveal slow-mixing
     community structure (used by core/community.clj).

   Backed by Apache Commons Math's EigenDecomposition for general
   real matrices."
  (:import [org.apache.commons.math3.linear
            Array2DRowRealMatrix
            EigenDecomposition]))

(defn- to-real-matrix
  "Convert vector-of-vectors P into Apache Commons Math RealMatrix."
  ^Array2DRowRealMatrix [P]
  (let [n (count P)
        arr (make-array Double/TYPE n n)]
    (doseq [i (range n)
            j (range n)]
      (aset-double arr i j (double (get-in P [i j]))))
    (Array2DRowRealMatrix. ^"[[D" arr)))

(defn eigen-decompose
  "Compute the eigen-decomposition of Markov kernel P.

   Returns:
     {:values     vector of real parts of eigenvalues
      :imaginary  vector of imaginary parts (paper §5 enhanced chains
                   require complex eigenvalues for periodic kernels)
      :magnitudes vector of |λᵢ|
      :vectors    matrix whose rows are right eigenvectors (real parts)}

   For a Markov kernel, the largest eigenvalue is 1 (Perron-Frobenius)
   and all others have magnitude ≤ 1."
  [P]
  (let [m (to-real-matrix P)
        decomp (EigenDecomposition. m)
        n (count P)
        real-parts (vec (.getRealEigenvalues decomp))
        imag-parts (vec (.getImagEigenvalues decomp))
        magnitudes (mapv (fn [r i] (Math/sqrt (+ (* r r) (* i i))))
                         real-parts imag-parts)
        ;; Right eigenvectors as rows
        V (.getV decomp)
        vectors (mapv (fn [i] (mapv #(.getEntry V i %) (range n)))
                      (range n))]
    {:values real-parts
     :imaginary imag-parts
     :magnitudes magnitudes
     :vectors vectors}))

(defn lambda-2
  "Second-largest eigenvalue magnitude of P.

   For an ergodic Markov kernel, λ₂ ∈ [0, 1) and the convergence rate
   to stationary distribution is governed by λ₂ — specifically, the
   total-variation distance from stationary at step k decays like
   |λ₂|^k. Smaller λ₂ means faster convergence."
  [P]
  (let [{:keys [magnitudes]} (eigen-decompose P)
        sorted-mags (sort > magnitudes)]
    (if (< (count sorted-mags) 2)
      0.0
      (nth sorted-mags 1))))

(def ^:private one-minus-eps (- 1.0 1.0e-9))

(defn convergence-half-life
  "Approximate steps for the chain to halve its TV-distance to
   stationary: ln(2) / -ln(λ₂). Returns Double/POSITIVE_INFINITY
   when λ₂ ≥ 1 (non-ergodic) or 0.0 when λ₂ ≤ 0 (already converged).

   Uses a tolerance on the upper bound to handle floating-point
   imprecision in eigenvalue computation (e.g., the period-2 NOT
   kernel has λ₂ that may compute to 1 − ε instead of exactly 1)."
  [P]
  (let [l2 (lambda-2 P)]
    (cond
      (>= l2 one-minus-eps) Double/POSITIVE_INFINITY
      (<= l2 0.0) 0.0
      :else (/ (Math/log 2) (- (Math/log l2))))))
