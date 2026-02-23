package com.example.motoristainteligente

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Gerencia a sincronização de dados com o Firebase Firestore.
 *
 * Estrutura no Firestore:
 * ```
 * users/{uid}/
 *   preferences    → configurações do motorista
 *   rides/{docId}  → cada corrida aceita
 *   sessions/{id}  → resumo de sessões de trabalho
 * ```
 *
 * Suporta autenticação anônima e Google Sign-In.
 * Os dados locais (SharedPreferences) continuam sendo a fonte primária,
 * o Firestore serve como backup e possível análise futura.
 */
class FirestoreManager(private val context: Context) {

    companion object {
        private const val TAG = "FirestoreManager"
        private const val COLLECTION_USERS = "users"
        private const val COLLECTION_RIDES = "rides"
        private const val COLLECTION_SESSIONS = "sessions"
        private const val DOC_PREFERENCES = "preferences"
    }

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /** UID do usuário autenticado. Null se não autenticado. */
    val uid: String? get() = auth.currentUser?.uid

    /** Se o usuário está autenticado */
    val isAuthenticated: Boolean get() = auth.currentUser != null

    /** Usuário Firebase atual */
    val currentUser: FirebaseUser? get() = auth.currentUser

    /** Se o usuário está logado com Google (não anônimo) */
    val isGoogleUser: Boolean get() = auth.currentUser?.providerData?.any {
        it.providerId == GoogleAuthProvider.PROVIDER_ID
    } == true

    /** Nome do usuário Google (ou null) */
    val displayName: String? get() = auth.currentUser?.displayName

    /** Email do usuário Google (ou null) */
    val email: String? get() = auth.currentUser?.email

    /** URL da foto de perfil do Google (ou null) */
    val photoUrl: String? get() = auth.currentUser?.photoUrl?.toString()

    // ========================
    // Autenticação Anônima
    // ========================

    /**
     * Inicia autenticação anônima.
     * Se já estiver autenticado, reutiliza a sessão existente.
     */
    fun signInAnonymously(onSuccess: () -> Unit = {}, onError: (Exception) -> Unit = {}) {
        if (auth.currentUser != null) {
            Log.d(TAG, "Já autenticado: ${auth.currentUser?.uid}")
            onSuccess()
            return
        }

        auth.signInAnonymously()
            .addOnSuccessListener {
                Log.d(TAG, "Auth anônima OK: ${it.user?.uid}")
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Erro auth anônima", e)
                onError(e)
            }
    }

    // ========================
    // Google Sign-In
    // ========================

    /**
     * Faz login com Google usando o ID token obtido via Credential Manager.
     * Se o usuário era anônimo, faz link das credenciais para preservar dados.
     */
    fun signInWithGoogle(
        idToken: String,
        onSuccess: (FirebaseUser?) -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val currentUser = auth.currentUser

        // Se tem usuário anônimo, vincular credencial Google para preservar dados
        if (currentUser != null && currentUser.isAnonymous) {
            currentUser.linkWithCredential(credential)
                .addOnSuccessListener { result ->
                    Log.d(TAG, "Conta anônima vinculada ao Google: ${result.user?.email}")
                    onSuccess(result.user)
                }
                .addOnFailureListener { e ->
                    // Se falhar o link (ex: conta Google já existe), fazer sign-in direto
                    Log.w(TAG, "Link falhou, tentando sign-in direto", e)
                    auth.signInWithCredential(credential)
                        .addOnSuccessListener { result ->
                            Log.d(TAG, "Google Sign-In direto OK: ${result.user?.email}")
                            onSuccess(result.user)
                        }
                        .addOnFailureListener { e2 ->
                            Log.e(TAG, "Erro Google Sign-In", e2)
                            onError(e2)
                        }
                }
        } else {
            // Sign-in direto com Google
            auth.signInWithCredential(credential)
                .addOnSuccessListener { result ->
                    Log.d(TAG, "Google Sign-In OK: ${result.user?.email}")
                    onSuccess(result.user)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Erro Google Sign-In", e)
                    onError(e)
                }
        }
    }

    /**
     * Faz logout do Firebase.
     */
    fun signOut() {
        auth.signOut()
        Log.d(TAG, "Logout realizado")
    }

    // ========================
    // Preferências do Motorista
    // ========================

    /**
     * Salva as preferências do motorista no Firestore.
     */
    fun savePreferences(prefs: DriverPreferences) {
        val uid = uid ?: return

        val data = hashMapOf(
            "minPricePerKm" to prefs.minPricePerKm,
            "minEarningsPerHour" to prefs.minEarningsPerHour,
            "maxPickupDistance" to prefs.maxPickupDistance,
            "maxRideDistance" to prefs.maxRideDistance,
            "updatedAt" to System.currentTimeMillis()
        )

        db.collection(COLLECTION_USERS).document(uid)
            .collection("config").document(DOC_PREFERENCES)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { Log.d(TAG, "Preferências salvas no Firestore") }
            .addOnFailureListener { Log.e(TAG, "Erro ao salvar preferências", it) }
    }

    /**
     * Carrega preferências do Firestore e aplica localmente.
     * Útil para restaurar configurações em um novo dispositivo.
     */
    fun loadPreferences(prefs: DriverPreferences, onComplete: () -> Unit = {}) {
        val uid = uid ?: run { onComplete(); return }

        db.collection(COLLECTION_USERS).document(uid)
            .collection("config").document(DOC_PREFERENCES)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    doc.getDouble("minPricePerKm")?.let { prefs.minPricePerKm = it }
                    doc.getDouble("minEarningsPerHour")?.let { prefs.minEarningsPerHour = it }
                    doc.getDouble("maxPickupDistance")?.let { prefs.maxPickupDistance = it }
                    doc.getDouble("maxRideDistance")?.let { prefs.maxRideDistance = it }
                    prefs.applyToAnalyzer()
                    Log.d(TAG, "Preferências carregadas do Firestore")
                }
                onComplete()
            }
            .addOnFailureListener {
                Log.e(TAG, "Erro ao carregar preferências", it)
                onComplete()
            }
    }

    // ========================
    // Histórico de Corridas
    // ========================

    /**
     * Salva uma corrida aceita no Firestore.
     */
    fun saveRide(ride: RideHistoryManager.AcceptedRide) {
        val uid = uid ?: return

        val data = hashMapOf(
            "timestamp" to ride.timestamp,
            "appSource" to ride.appSource,
            "price" to ride.price,
            "distanceKm" to ride.distanceKm,
            "estimatedTimeMin" to ride.estimatedTimeMin,
            "pricePerKm" to ride.pricePerKm,
            "earningsPerHour" to ride.earningsPerHour,
            "score" to ride.score,
            "recommendation" to ride.recommendation,
            "pickupDistanceKm" to ride.pickupDistanceKm,
            "date" to ride.date
        )

        db.collection(COLLECTION_USERS).document(uid)
            .collection(COLLECTION_RIDES)
            .add(data)
            .addOnSuccessListener { Log.d(TAG, "Corrida salva: ${it.id}") }
            .addOnFailureListener { Log.e(TAG, "Erro ao salvar corrida", it) }
    }

    /**
     * Sincroniza todo o histórico local para o Firestore.
     * Usa batch write para eficiência.
     */
    fun syncAllRides(historyManager: RideHistoryManager, onComplete: () -> Unit = {}) {
        val uid = uid ?: run { onComplete(); return }

        val rides = historyManager.getAll()
        if (rides.isEmpty()) {
            onComplete()
            return
        }

        // Limpar coleção existente e reenviar tudo
        val ridesRef = db.collection(COLLECTION_USERS).document(uid)
            .collection(COLLECTION_RIDES)

        // Enviar em lotes de 500 (limite do Firestore batch)
        val batches = rides.chunked(500)
        var completedBatches = 0

        batches.forEach { batch ->
            val writeBatch = db.batch()
            batch.forEach { ride ->
                val docRef = ridesRef.document("${ride.timestamp}")
                writeBatch.set(docRef, hashMapOf(
                    "timestamp" to ride.timestamp,
                    "appSource" to ride.appSource,
                    "price" to ride.price,
                    "distanceKm" to ride.distanceKm,
                    "estimatedTimeMin" to ride.estimatedTimeMin,
                    "pricePerKm" to ride.pricePerKm,
                    "earningsPerHour" to ride.earningsPerHour,
                    "score" to ride.score,
                    "recommendation" to ride.recommendation,
                    "pickupDistanceKm" to ride.pickupDistanceKm,
                    "date" to ride.date
                ))
            }

            writeBatch.commit()
                .addOnSuccessListener {
                    completedBatches++
                    Log.d(TAG, "Batch $completedBatches/${batches.size} sincronizado")
                    if (completedBatches == batches.size) onComplete()
                }
                .addOnFailureListener {
                    Log.e(TAG, "Erro ao sincronizar batch", it)
                    completedBatches++
                    if (completedBatches == batches.size) onComplete()
                }
        }
    }

    /**
     * Carrega corridas do Firestore para o histórico local.
     * Útil para restaurar dados em novo dispositivo.
     */
    fun loadRides(
        historyManager: RideHistoryManager,
        onComplete: (Int) -> Unit = {}
    ) {
        val uid = uid ?: run { onComplete(0); return }

        db.collection(COLLECTION_USERS).document(uid)
            .collection(COLLECTION_RIDES)
            .orderBy("timestamp")
            .get()
            .addOnSuccessListener { snapshot ->
                var count = 0
                snapshot.documents.forEach { doc ->
                    try {
                        historyManager.recordRide(
                            appSource = doc.getString("appSource") ?: "",
                            price = doc.getDouble("price") ?: 0.0,
                            distanceKm = doc.getDouble("distanceKm") ?: 0.0,
                            timeMin = (doc.getLong("estimatedTimeMin") ?: 0).toInt(),
                            pricePerKm = doc.getDouble("pricePerKm") ?: 0.0,
                            earningsPerHour = doc.getDouble("earningsPerHour") ?: 0.0,
                            score = (doc.getLong("score") ?: 0).toInt(),
                            pickupDistanceKm = doc.getDouble("pickupDistanceKm") ?: 0.0
                        )
                        count++
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao carregar corrida ${doc.id}", e)
                    }
                }
                Log.d(TAG, "$count corridas carregadas do Firestore")
                onComplete(count)
            }
            .addOnFailureListener {
                Log.e(TAG, "Erro ao carregar corridas", it)
                onComplete(0)
            }
    }

    // ========================
    // Sessões de trabalho
    // ========================

    /**
     * Salva o resumo de uma sessão de trabalho.
     */
    fun saveSessionSummary(stats: DemandTracker.DemandStats) {
        val uid = uid ?: return

        val data = hashMapOf(
            "timestamp" to System.currentTimeMillis(),
            "durationMin" to stats.sessionDurationMin,
            "totalEarnings" to stats.sessionTotalEarnings,
            "avgEarningsPerHour" to stats.sessionAvgEarningsPerHour,
            "totalRidesUber" to stats.totalRidesUber,
            "totalRides99" to stats.totalRides99,
            "acceptedRidesTotal" to stats.acceptedRidesTotal,
            "offersPerHourUber" to stats.offersPerHourUber,
            "offersPerHour99" to stats.offersPerHour99
        )

        db.collection(COLLECTION_USERS).document(uid)
            .collection(COLLECTION_SESSIONS)
            .add(data)
            .addOnSuccessListener { Log.d(TAG, "Sessão salva: ${it.id}") }
            .addOnFailureListener { Log.e(TAG, "Erro ao salvar sessão", it) }
    }
}
