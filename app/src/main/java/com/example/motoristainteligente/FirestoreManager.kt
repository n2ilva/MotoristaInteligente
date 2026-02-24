package com.example.motoristainteligente

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.util.Calendar

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
        private const val COLLECTION_RIDE_OFFERS = "ride_offers"
        private const val COLLECTION_SESSIONS = "sessions"
        private const val COLLECTION_REGIONAL_OFFERS = "regional_ride_offers"
        private const val COLLECTION_DRIVER_LOCATIONS = "driver_locations"
        private const val DOC_PREFERENCES = "preferences"
        private const val DRIVER_LOCATION_EXPIRY_MS = 30 * 60 * 1000L // 30 min — considera motorista "online"
    }

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var lastKnownCityForOffers: String? = null
    private var lastKnownNeighborhoodForOffers: String? = null

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

    private fun ensureAuthenticated(
        onReady: (String) -> Unit,
        onError: (Exception) -> Unit = {}
    ) {
        val currentUid = uid
        if (!currentUid.isNullOrBlank()) {
            onReady(currentUid)
            return
        }

        signInAnonymously(
            onSuccess = {
                val authenticatedUid = uid
                if (!authenticatedUid.isNullOrBlank()) {
                    onReady(authenticatedUid)
                } else {
                    onError(IllegalStateException("UID indisponível após autenticação"))
                }
            },
            onError = onError
        )
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
    // Ofertas de Corridas (todas que chegam)
    // ========================

    /**
     * Salva TODA oferta de corrida detectada no Firestore.
     * Usado para calcular médias de demanda, preços e padrões.
     *
     * Estrutura:
     *   users/{uid}/ride_offers/{auto-id}       → dados pessoais do motorista
     *   regional_ride_offers/{auto-id}           → dados globais para análise regional
     */
    fun saveRideOffer(rideData: RideData, city: String? = null, neighborhood: String? = null) {
        if (!city.isNullOrBlank()) {
            lastKnownCityForOffers = city
            lastKnownNeighborhoodForOffers = neighborhood
        }

        val resolvedCity = city?.takeIf { it.isNotBlank() } ?: lastKnownCityForOffers
        val resolvedNeighborhood = neighborhood ?: lastKnownNeighborhoodForOffers

        ensureAuthenticated(
            onReady = { userId ->
                val data = hashMapOf(
                    "timestamp" to rideData.timestamp,
                    "appSource" to rideData.appSource.displayName,
                    "ridePrice" to rideData.ridePrice,
                    "distanceKm" to rideData.distanceKm,
                    "estimatedTimeMin" to rideData.estimatedTimeMin,
                    "pickupDistanceKm" to rideData.pickupDistanceKm,
                    "pickupTimeMin" to rideData.pickupTimeMin,
                    "userRating" to rideData.userRating,
                    "pickupAddress" to rideData.pickupAddress,
                    "dropoffAddress" to rideData.dropoffAddress,
                    "extractionSource" to rideData.extractionSource,
                    "city" to (resolvedCity ?: ""),
                    "neighborhood" to (resolvedNeighborhood ?: "")
                )

                // Salvar no escopo do usuário
                db.collection(COLLECTION_USERS).document(userId)
                    .collection(COLLECTION_RIDE_OFFERS)
                    .add(data)
                    .addOnSuccessListener {
                        Log.d(
                            TAG,
                            "Oferta salva: ${it.id} (R$ ${rideData.ridePrice}, city=${resolvedCity ?: "-"})"
                        )
                    }
                    .addOnFailureListener { Log.e(TAG, "Erro ao salvar oferta", it) }

                // Salvar na coleção global (regional) para análise de demanda por região
                if (!resolvedCity.isNullOrBlank()) {
                    val regionalData = hashMapOf(
                        "timestamp" to rideData.timestamp,
                        "appSource" to rideData.appSource.displayName,
                        "ridePrice" to rideData.ridePrice,
                        "distanceKm" to rideData.distanceKm,
                        "estimatedTimeMin" to rideData.estimatedTimeMin,
                        "pickupDistanceKm" to rideData.pickupDistanceKm,
                        "city" to resolvedCity,
                        "neighborhood" to (resolvedNeighborhood ?: ""),
                        "uid" to userId
                    )

                    db.collection(COLLECTION_REGIONAL_OFFERS)
                        .add(regionalData)
                        .addOnSuccessListener { Log.d(TAG, "Oferta regional salva: $resolvedCity / $resolvedNeighborhood") }
                        .addOnFailureListener { Log.e(TAG, "Erro ao salvar oferta regional", it) }
                } else {
                    Log.w(TAG, "Oferta salva sem city — regional_ride_offers não atualizado nesta oferta")
                }
            },
            onError = { e ->
                Log.e(TAG, "Falha de autenticação ao salvar oferta", e)
            }
        )
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

    // ========================
    // Estatísticas de Ofertas (leitura do Firebase)
    // ========================

    /**
     * Estatísticas agregadas das ofertas coletadas do Firebase.
     */
    data class RideOfferStats(
        val totalOffersToday: Int = 0,
        val offersUber: Int = 0,
        val offers99: Int = 0,
        val avgPrice: Double = 0.0,
        val avgPricePerKm: Double = 0.0,
        val avgDistanceKm: Double = 0.0,
        val avgEstimatedTimeMin: Double = 0.0,
        val avgPriceUber: Double = 0.0,
        val avgPrice99: Double = 0.0,
        val avgPricePerKmUber: Double = 0.0,
        val avgPricePerKm99: Double = 0.0,
        val offersLast1h: Int = 0,
        val offersLast3h: Int = 0,
        val bestPricePerKm: Double = 0.0,
        val worstPricePerKm: Double = 0.0,
        val lastOfferTimestamp: Long = 0L,
        val loaded: Boolean = false
    )

    /**
     * Carrega todas as ofertas de hoje do Firebase e calcula estatísticas agregadas.
     * Retorna via callback pois a leitura é assíncrona.
     */
    fun loadTodayRideOfferStats(onResult: (RideOfferStats) -> Unit) {
        ensureAuthenticated(
            onReady = { uid ->
                // Início do dia atual (meia-noite)
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val todayStart = calendar.timeInMillis

                db.collection(COLLECTION_USERS).document(uid)
                    .collection(COLLECTION_RIDE_OFFERS)
                    .whereGreaterThanOrEqualTo("timestamp", todayStart)
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .get()
                    .addOnSuccessListener { snapshot ->
                        if (snapshot.isEmpty) {
                            onResult(RideOfferStats(loaded = true))
                            return@addOnSuccessListener
                        }

                val now = System.currentTimeMillis()
                val oneHourAgo = now - 60 * 60 * 1000L
                val threeHoursAgo = now - 3 * 60 * 60 * 1000L

                val allPrices = mutableListOf<Double>()
                val allPricePerKm = mutableListOf<Double>()
                val allDistances = mutableListOf<Double>()
                val allTimes = mutableListOf<Double>()

                val uberPrices = mutableListOf<Double>()
                val uberPricePerKm = mutableListOf<Double>()
                val ninetyNinePrices = mutableListOf<Double>()
                val ninetyNinePricePerKm = mutableListOf<Double>()

                var offersUber = 0
                var offers99 = 0
                var offersLast1h = 0
                var offersLast3h = 0
                var lastTimestamp = 0L

                for (doc in snapshot.documents) {
                    val timestamp = doc.getLong("timestamp") ?: 0L
                    val price = doc.getDouble("ridePrice") ?: 0.0
                    val distance = doc.getDouble("distanceKm") ?: 0.0
                    val time = doc.getDouble("estimatedTimeMin") ?: doc.getLong("estimatedTimeMin")?.toDouble() ?: 0.0
                    val app = doc.getString("appSource") ?: ""
                    val pricePerKm = if (distance > 0) price / distance else 0.0

                    if (timestamp > lastTimestamp) lastTimestamp = timestamp

                    allPrices.add(price)
                    if (pricePerKm > 0) allPricePerKm.add(pricePerKm)
                    if (distance > 0) allDistances.add(distance)
                    if (time > 0) allTimes.add(time)

                    if (timestamp >= oneHourAgo) offersLast1h++
                    if (timestamp >= threeHoursAgo) offersLast3h++

                    when {
                        app.contains("Uber", ignoreCase = true) -> {
                            offersUber++
                            uberPrices.add(price)
                            if (pricePerKm > 0) uberPricePerKm.add(pricePerKm)
                        }
                        app.contains("99", ignoreCase = true) -> {
                            offers99++
                            ninetyNinePrices.add(price)
                            if (pricePerKm > 0) ninetyNinePricePerKm.add(pricePerKm)
                        }
                    }
                }

                        val stats = RideOfferStats(
                    totalOffersToday = snapshot.size(),
                    offersUber = offersUber,
                    offers99 = offers99,
                    avgPrice = allPrices.averageOrZero(),
                    avgPricePerKm = allPricePerKm.averageOrZero(),
                    avgDistanceKm = allDistances.averageOrZero(),
                    avgEstimatedTimeMin = allTimes.averageOrZero(),
                    avgPriceUber = uberPrices.averageOrZero(),
                    avgPrice99 = ninetyNinePrices.averageOrZero(),
                    avgPricePerKmUber = uberPricePerKm.averageOrZero(),
                    avgPricePerKm99 = ninetyNinePricePerKm.averageOrZero(),
                    offersLast1h = offersLast1h,
                    offersLast3h = offersLast3h,
                    bestPricePerKm = allPricePerKm.maxOrNull() ?: 0.0,
                    worstPricePerKm = allPricePerKm.minOrNull() ?: 0.0,
                    lastOfferTimestamp = lastTimestamp,
                    loaded = true
                )

                        Log.d(TAG, "Ofertas hoje: ${stats.totalOffersToday} (Uber: $offersUber, 99: $offers99)")
                        onResult(stats)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Erro ao carregar ofertas de hoje", e)
                        onResult(RideOfferStats(loaded = true))
                    }
            },
            onError = {
                onResult(RideOfferStats(loaded = true))
            }
        )
    }

    /**
     * Carrega ofertas do dia do Firebase e popula o DemandTracker.
     * Chamado ao abrir o app para restaurar dados do dia atual na memória.
     */
    fun loadTodayOffersIntoDemandTracker(onComplete: () -> Unit = {}) {
        ensureAuthenticated(
            onReady = { uid ->
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val todayStart = calendar.timeInMillis

                db.collection(COLLECTION_USERS).document(uid)
                    .collection(COLLECTION_RIDE_OFFERS)
                    .whereGreaterThanOrEqualTo("timestamp", todayStart)
                    .orderBy("timestamp", Query.Direction.ASCENDING)
                    .get()
                    .addOnSuccessListener { snapshot ->
                        if (snapshot.isEmpty) {
                            Log.d(TAG, "Nenhuma oferta hoje para restaurar no DemandTracker")
                            onComplete()
                            return@addOnSuccessListener
                        }

                        val offers = snapshot.documents.mapNotNull { doc ->
                            val timestamp = doc.getLong("timestamp") ?: return@mapNotNull null
                            val price = doc.getDouble("ridePrice") ?: return@mapNotNull null
                            val distance = doc.getDouble("distanceKm") ?: 0.0
                            val time = doc.getDouble("estimatedTimeMin")?.toInt()
                                ?: doc.getLong("estimatedTimeMin")?.toInt() ?: 0
                            val app = doc.getString("appSource") ?: ""
                            val pricePerKm = if (distance > 0) price / distance else 0.0
                            val appSource = when {
                                app.contains("Uber", ignoreCase = true) -> AppSource.UBER
                                app.contains("99", ignoreCase = true) -> AppSource.NINETY_NINE
                                else -> AppSource.UNKNOWN
                            }

                            DemandTracker.RideSnapshot(
                                timestamp = timestamp,
                                price = price,
                                distanceKm = distance,
                                estimatedTimeMin = time,
                                pricePerKm = pricePerKm,
                                appSource = appSource
                            )
                        }

                        DemandTracker.restoreFromFirebase(offers)
                        Log.d(TAG, "DemandTracker restaurado com ${offers.size} ofertas do dia")
                        onComplete()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Erro ao restaurar DemandTracker do Firebase", e)
                        onComplete()
                    }
            },
            onError = {
                onComplete()
            }
        )
    }

    private fun List<Double>.averageOrZero(): Double =
        if (isEmpty()) 0.0 else average()

    // ========================
    // Demanda Regional (dados globais de todos os motoristas)
    // ========================

    /**
     * Estatísticas de demanda por região (Cidade / Bairro).
     */
    /**
     * Representa uma oferta individual da coleção regional.
     */
    data class RegionalOffer(
        val timestamp: Long = 0L,
        val appSource: String = "",
        val ridePrice: Double = 0.0,
        val distanceKm: Double = 0.0,
        val estimatedTimeMin: Double = 0.0,
        val pickupDistanceKm: Double = 0.0,
        val neighborhood: String = ""
    )

    data class RegionalDemandStats(
        val city: String = "",
        val neighborhood: String = "",
        val totalOffers: Int = 0,
        val offersUber: Int = 0,
        val offers99: Int = 0,
        val avgPrice: Double = 0.0,
        val avgPricePerKm: Double = 0.0,
        val avgDistanceKm: Double = 0.0,
        val avgEstimatedTimeMin: Double = 0.0,
        val avgPickupDistanceKm: Double = 0.0,
        val activeDrivers: Int = 0,
        val onlineDrivers: Int = 0,
        val offersLast1h: Int = 0,
        val offersLast3h: Int = 0,
        val bestPricePerKm: Double = 0.0,
        val worstPricePerKm: Double = 0.0,
        val avgPriceUber: Double = 0.0,
        val avgPrice99: Double = 0.0,
        val loaded: Boolean = false
    )

    /**
     * Carrega as últimas ofertas individuais de uma região (últimas 24h).
     * Retorna lista ordenada por timestamp decrescente (mais recente primeiro).
     */
    fun loadRecentRegionalOffers(
        city: String,
        neighborhood: String? = null,
        limit: Int = 50,
        onResult: (List<RegionalOffer>) -> Unit
    ) {
        val last24h = System.currentTimeMillis() - 24 * 60 * 60 * 1000L

        var query = db.collection(COLLECTION_REGIONAL_OFFERS)
            .whereEqualTo("city", city)
            .whereGreaterThanOrEqualTo("timestamp", last24h)

        if (!neighborhood.isNullOrBlank()) {
            query = query.whereEqualTo("neighborhood", neighborhood)
        }

        query.get()
            .addOnSuccessListener { snapshot ->
                val offers = snapshot.documents.mapNotNull { doc ->
                    val ts = doc.getLong("timestamp") ?: return@mapNotNull null
                    val price = doc.getDouble("ridePrice") ?: 0.0
                    if (price <= 0) return@mapNotNull null
                    RegionalOffer(
                        timestamp = ts,
                        appSource = doc.getString("appSource") ?: "",
                        ridePrice = price,
                        distanceKm = doc.getDouble("distanceKm") ?: 0.0,
                        estimatedTimeMin = doc.getDouble("estimatedTimeMin")
                            ?: doc.getLong("estimatedTimeMin")?.toDouble() ?: 0.0,
                        pickupDistanceKm = doc.getDouble("pickupDistanceKm") ?: 0.0,
                        neighborhood = doc.getString("neighborhood") ?: ""
                    )
                }.sortedByDescending { it.timestamp }.take(limit)

                Log.d(TAG, "Ofertas regionais carregadas: ${offers.size} para $city/${neighborhood ?: "todos"}")
                onResult(offers)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Erro ao carregar ofertas regionais", e)
                onResult(emptyList())
            }
    }

    /**
     * Salva/atualiza a localização do motorista no Firebase.
     * Usa o UID como document ID para que cada motorista tenha apenas 1 registro.
     * Os dados expiram após 30 min (consultados pelo campo updatedAt).
     *
     * Chamado a cada 15 min pelo FloatingAnalyticsService.
     */
    fun saveDriverLocation(city: String, neighborhood: String?, latitude: Double, longitude: Double) {
        if (city.isBlank()) return

        lastKnownCityForOffers = city
        lastKnownNeighborhoodForOffers = neighborhood

        ensureAuthenticated(
            onReady = { uid ->
                val data = hashMapOf(
                    "uid" to uid,
                    "city" to city,
                    "neighborhood" to (neighborhood ?: ""),
                    "latitude" to latitude,
                    "longitude" to longitude,
                    "updatedAt" to System.currentTimeMillis()
                )

                db.collection(COLLECTION_DRIVER_LOCATIONS)
                    .document(uid) // 1 registro por motorista, atualizado in-place
                    .set(data)
                    .addOnSuccessListener { Log.d(TAG, "Localização salva: $city / $neighborhood") }
                    .addOnFailureListener { Log.e(TAG, "Erro ao salvar localização", it) }
            },
            onError = { e ->
                Log.e(TAG, "Falha de autenticação ao salvar localização", e)
            }
        )
    }

    /**
     * Remove a localização do motorista (quando o serviço é desligado).
     */
    fun removeDriverLocation() {
        val uid = uid ?: return
        db.collection(COLLECTION_DRIVER_LOCATIONS)
            .document(uid)
            .delete()
            .addOnSuccessListener { Log.d(TAG, "Localização do motorista removida") }
            .addOnFailureListener { Log.e(TAG, "Erro ao remover localização", it) }
    }

    /**
     * Conta quantos motoristas estão online em uma cidade (e opcionalmente bairro).
     * "Online" = updatedAt nos últimos 30 minutos.
     */
    fun loadOnlineDriversForCity(
        city: String,
        neighborhood: String? = null,
        onResult: (Int) -> Unit
    ) {
        val expiryThreshold = System.currentTimeMillis() - DRIVER_LOCATION_EXPIRY_MS

        var query = db.collection(COLLECTION_DRIVER_LOCATIONS)
            .whereEqualTo("city", city)
            .whereGreaterThanOrEqualTo("updatedAt", expiryThreshold)

        if (!neighborhood.isNullOrBlank()) {
            query = query.whereEqualTo("neighborhood", neighborhood)
        }

        query.get()
            .addOnSuccessListener { snapshot ->
                Log.d(TAG, "Motoristas online em $city/${neighborhood ?: "todos"}: ${snapshot.size()}")
                onResult(snapshot.size())
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Erro ao contar motoristas online", e)
                onResult(0)
            }
    }

    /**
     * Retorna lista de todas as cidades com motoristas online ou ofertas recentes.
     * Combina dados de driver_locations e regional_ride_offers.
     */
    fun loadAvailableCitiesWithDrivers(onResult: (List<String>) -> Unit) {
        val citiesSet = mutableSetOf<String>()
        var completedQueries = 0
        val totalQueries = 2

        fun checkDone() {
            completedQueries++
            if (completedQueries >= totalQueries) {
                onResult(citiesSet.filter { it.isNotBlank() }.sorted())
            }
        }

        // Cidades das ofertas regionais
        db.collection(COLLECTION_REGIONAL_OFFERS)
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.documents.mapNotNull { it.getString("city") }
                    .filter { it.isNotBlank() }
                    .forEach { citiesSet.add(it) }
                checkDone()
            }
            .addOnFailureListener { checkDone() }

        // Cidades dos motoristas online
        val expiryThreshold = System.currentTimeMillis() - DRIVER_LOCATION_EXPIRY_MS
        db.collection(COLLECTION_DRIVER_LOCATIONS)
            .whereGreaterThanOrEqualTo("updatedAt", expiryThreshold)
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.documents.mapNotNull { it.getString("city") }
                    .filter { it.isNotBlank() }
                    .forEach { citiesSet.add(it) }
                checkDone()
            }
            .addOnFailureListener { checkDone() }
    }

    /**
     * Retorna lista de todas as cidades que possuem ofertas regionais.
     */
    fun loadAvailableCities(onResult: (List<String>) -> Unit) {
        db.collection(COLLECTION_REGIONAL_OFFERS)
            .get()
            .addOnSuccessListener { snapshot ->
                val cities = snapshot.documents
                    .mapNotNull { it.getString("city") }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                onResult(cities)
            }
            .addOnFailureListener {
                Log.e(TAG, "Erro ao listar cidades", it)
                onResult(emptyList())
            }
    }

    /**
     * Retorna lista de bairros disponíveis para uma cidade específica.
     */
    fun loadNeighborhoodsForCity(city: String, onResult: (List<String>) -> Unit) {
        db.collection(COLLECTION_REGIONAL_OFFERS)
            .whereEqualTo("city", city)
            .get()
            .addOnSuccessListener { snapshot ->
                val neighborhoods = snapshot.documents
                    .mapNotNull { it.getString("neighborhood") }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                onResult(neighborhoods)
            }
            .addOnFailureListener {
                Log.e(TAG, "Erro ao listar bairros de $city", it)
                onResult(emptyList())
            }
    }

    /**
     * Carrega estatísticas de demanda regional filtradas por cidade e bairro (opcional).
     * Considera dados das últimas 24 horas.
     */
    fun loadRegionalDemandStats(
        city: String,
        neighborhood: String? = null,
        onResult: (RegionalDemandStats) -> Unit
    ) {
        val last24h = System.currentTimeMillis() - 24 * 60 * 60 * 1000L

        var query = db.collection(COLLECTION_REGIONAL_OFFERS)
            .whereEqualTo("city", city)
            .whereGreaterThanOrEqualTo("timestamp", last24h)

        if (!neighborhood.isNullOrBlank()) {
            query = query.whereEqualTo("neighborhood", neighborhood)
        }

        // Buscar motoristas online em paralelo
        var onlineCount = 0
        var onlineLoaded = false
        var offersLoaded = false
        var offersResult: RegionalDemandStats? = null

        fun mergeAndDeliver() {
            if (onlineLoaded && offersLoaded) {
                val merged = offersResult?.copy(onlineDrivers = onlineCount)
                    ?: RegionalDemandStats(city = city, neighborhood = neighborhood ?: "", loaded = true)
                onResult(merged)
            }
        }

        loadOnlineDriversForCity(city, neighborhood) { count ->
            onlineCount = count
            onlineLoaded = true
            mergeAndDeliver()
        }

        query.get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    offersResult = RegionalDemandStats(
                        city = city,
                        neighborhood = neighborhood ?: "",
                        loaded = true
                    )
                    offersLoaded = true
                    mergeAndDeliver()
                    return@addOnSuccessListener
                }

                val now = System.currentTimeMillis()
                val oneHourAgo = now - 60 * 60 * 1000L
                val threeHoursAgo = now - 3 * 60 * 60 * 1000L

                val allPrices = mutableListOf<Double>()
                val allPricePerKm = mutableListOf<Double>()
                val allDistances = mutableListOf<Double>()
                val allTimes = mutableListOf<Double>()
                val allPickupDistances = mutableListOf<Double>()
                val uberPrices = mutableListOf<Double>()
                val ninetyNinePrices = mutableListOf<Double>()
                val uniqueDrivers = mutableSetOf<String>()

                var offersUber = 0
                var offers99 = 0
                var offersLast1h = 0
                var offersLast3h = 0

                for (doc in snapshot.documents) {
                    val timestamp = doc.getLong("timestamp") ?: 0L
                    val price = doc.getDouble("ridePrice") ?: 0.0
                    val distance = doc.getDouble("distanceKm") ?: 0.0
                    val time = doc.getDouble("estimatedTimeMin") ?: doc.getLong("estimatedTimeMin")?.toDouble() ?: 0.0
                    val pickupDist = doc.getDouble("pickupDistanceKm") ?: 0.0
                    val app = doc.getString("appSource") ?: ""
                    val driverUid = doc.getString("uid") ?: ""
                    val pricePerKm = if (distance > 0) price / distance else 0.0

                    if (driverUid.isNotBlank()) uniqueDrivers.add(driverUid)

                    if (price > 0) allPrices.add(price)
                    if (pricePerKm > 0) allPricePerKm.add(pricePerKm)
                    if (distance > 0) allDistances.add(distance)
                    if (time > 0) allTimes.add(time)
                    if (pickupDist > 0) allPickupDistances.add(pickupDist)

                    if (timestamp >= oneHourAgo) offersLast1h++
                    if (timestamp >= threeHoursAgo) offersLast3h++

                    when {
                        app.contains("Uber", ignoreCase = true) -> {
                            offersUber++
                            if (price > 0) uberPrices.add(price)
                        }
                        app.contains("99", ignoreCase = true) -> {
                            offers99++
                            if (price > 0) ninetyNinePrices.add(price)
                        }
                    }
                }

                offersResult = RegionalDemandStats(
                    city = city,
                    neighborhood = neighborhood ?: "",
                    totalOffers = snapshot.size(),
                    offersUber = offersUber,
                    offers99 = offers99,
                    avgPrice = allPrices.averageOrZero(),
                    avgPricePerKm = allPricePerKm.averageOrZero(),
                    avgDistanceKm = allDistances.averageOrZero(),
                    avgEstimatedTimeMin = allTimes.averageOrZero(),
                    avgPickupDistanceKm = allPickupDistances.averageOrZero(),
                    activeDrivers = uniqueDrivers.size,
                    offersLast1h = offersLast1h,
                    offersLast3h = offersLast3h,
                    bestPricePerKm = allPricePerKm.maxOrNull() ?: 0.0,
                    worstPricePerKm = allPricePerKm.minOrNull() ?: 0.0,
                    avgPriceUber = uberPrices.averageOrZero(),
                    avgPrice99 = ninetyNinePrices.averageOrZero(),
                    loaded = true
                )

                Log.d(TAG, "Demanda regional $city/${neighborhood ?: "todos"}: ${offersResult!!.totalOffers} ofertas, ${offersResult!!.activeDrivers} motoristas")
                offersLoaded = true
                mergeAndDeliver()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Erro ao carregar demanda regional", e)
                offersResult = RegionalDemandStats(city = city, neighborhood = neighborhood ?: "", loaded = true)
                offersLoaded = true
                mergeAndDeliver()
            }
    }
}
