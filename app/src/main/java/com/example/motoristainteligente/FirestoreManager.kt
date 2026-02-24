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
        private const val COLLECTION_REGIONAL_DEMAND_15M = "regional_demand_15m"
        private const val COLLECTION_DRIVER_LOCATIONS = "driver_locations"
        private const val COLLECTION_ANALYTICS_ONLINE_LOGS = "analytics_online_logs"
        private const val COLLECTION_ANALYTICS_OFFER_LOGS = "analytics_offer_logs"
        private const val COLLECTION_ANALYTICS_DRIVER_DAILY = "analytics_driver_daily"
        private const val DOC_PREFERENCES = "preferences"
        private const val DRIVER_LOCATION_EXPIRY_MS = 30 * 60 * 1000L // 30 min — considera motorista "online"
        private const val DEMAND_BUCKET_10M_MS = 10 * 60 * 1000L
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

        ensureAuthenticated(
            onReady = { userId ->
                resolveOfferLocation(
                    userId = userId,
                    city = city,
                    neighborhood = neighborhood
                ) { resolvedCity, resolvedNeighborhood ->
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

                    // Atualizar agregados de demanda regional por janela de 10 minutos
                    if (!resolvedCity.isNullOrBlank()) {
                        updateRegionalDemand15m(
                            city = resolvedCity,
                            neighborhood = resolvedNeighborhood,
                            appSource = rideData.appSource.displayName,
                            driverId = userId
                        )
                    } else {
                        Log.w(TAG, "Oferta salva sem city — demanda regional de 10min não atualizada nesta oferta")
                    }

                    // Log analítico separado para futuras análises
                    saveOfferAnalyticsLog(
                        uid = userId,
                        rideData = rideData,
                        city = resolvedCity,
                        neighborhood = resolvedNeighborhood
                    )
                }
            },
            onError = { e ->
                Log.e(TAG, "Falha de autenticação ao salvar oferta", e)
            }
        )
    }

    private fun resolveOfferLocation(
        userId: String,
        city: String?,
        neighborhood: String?,
        onResolved: (String?, String?) -> Unit
    ) {
        val initialCity = city?.takeIf { it.isNotBlank() } ?: lastKnownCityForOffers
        val initialNeighborhood = neighborhood ?: lastKnownNeighborhoodForOffers

        if (!initialCity.isNullOrBlank()) {
            onResolved(initialCity, initialNeighborhood)
            return
        }

        db.collection(COLLECTION_DRIVER_LOCATIONS)
            .document(userId)
            .get()
            .addOnSuccessListener { doc ->
                val fallbackCity = doc.getString("city")?.trim().orEmpty().ifBlank { null }
                val fallbackNeighborhood = doc.getString("neighborhood")?.trim().orEmpty().ifBlank { null }

                if (!fallbackCity.isNullOrBlank()) {
                    lastKnownCityForOffers = fallbackCity
                    lastKnownNeighborhoodForOffers = fallbackNeighborhood
                    Log.d(TAG, "Fallback de localização aplicado para oferta: $fallbackCity / ${fallbackNeighborhood ?: "-"}")
                }

                onResolved(fallbackCity ?: initialCity, fallbackNeighborhood ?: initialNeighborhood)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Falha ao buscar fallback em driver_locations para oferta", e)
                onResolved(initialCity, initialNeighborhood)
            }
    }

    private fun saveOfferAnalyticsLog(
        uid: String,
        rideData: RideData,
        city: String?,
        neighborhood: String?
    ) {
        val timestamp = rideData.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
        val appName = rideData.appSource.displayName
        val isUber = isUberSource(appName)
        val isNinetyNine = isNinetyNineSource(appName)

        val data = hashMapOf(
            "uid" to uid,
            "timestamp" to timestamp,
            "dateKey" to dayKeyFromTimestamp(timestamp),
            "city" to (city ?: ""),
            "neighborhood" to (neighborhood ?: ""),
            "appSource" to appName,
            "offersUber" to if (isUber) 1 else 0,
            "offers99" to if (isNinetyNine) 1 else 0,
            "offerCount" to 1,
            "ridePrice" to rideData.ridePrice,
            "distanceKm" to rideData.distanceKm,
            "estimatedTimeMin" to rideData.estimatedTimeMin,
            "pickupDistanceKm" to rideData.pickupDistanceKm,
            "createdAt" to System.currentTimeMillis()
        )

        db.collection(COLLECTION_ANALYTICS_OFFER_LOGS)
            .add(data)
            .addOnFailureListener { e ->
                Log.e(TAG, "Erro ao salvar analytics_offer_logs", e)
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
        val avgDistanceKmUber: Double = 0.0,
        val avgDistanceKm99: Double = 0.0,
        val avgEstimatedTimeMinUber: Double = 0.0,
        val avgEstimatedTimeMin99: Double = 0.0,
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
                val todayStart = todayStartMillis()

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

                data class MetricAccumulator(
                    var offers: Int = 0,
                    var sumPrice: Double = 0.0,
                    var countPrice: Int = 0,
                    var sumDistance: Double = 0.0,
                    var countDistance: Int = 0,
                    var sumTime: Double = 0.0,
                    var countTime: Int = 0,
                    var sumPricePerKm: Double = 0.0,
                    var countPricePerKm: Int = 0
                )

                fun average(sum: Double, count: Int): Double = if (count > 0) sum / count else 0.0

                val all = MetricAccumulator()
                val uber = MetricAccumulator()
                val ninetyNine = MetricAccumulator()

                val allPricePerKmValues = mutableListOf<Double>()

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

                    all.offers++
                    if (price > 0) {
                        all.sumPrice += price
                        all.countPrice++
                    }
                    if (distance > 0) {
                        all.sumDistance += distance
                        all.countDistance++
                    }
                    if (time > 0) {
                        all.sumTime += time
                        all.countTime++
                    }
                    if (pricePerKm > 0) {
                        all.sumPricePerKm += pricePerKm
                        all.countPricePerKm++
                        allPricePerKmValues.add(pricePerKm)
                    }

                    if (timestamp >= oneHourAgo) offersLast1h++
                    if (timestamp >= threeHoursAgo) offersLast3h++

                    when {
                        isUberSource(app) -> {
                            offersUber++
                            uber.offers++
                            if (price > 0) {
                                uber.sumPrice += price
                                uber.countPrice++
                            }
                            if (distance > 0) {
                                uber.sumDistance += distance
                                uber.countDistance++
                            }
                            if (time > 0) {
                                uber.sumTime += time
                                uber.countTime++
                            }
                            if (pricePerKm > 0) {
                                uber.sumPricePerKm += pricePerKm
                                uber.countPricePerKm++
                            }
                        }
                        isNinetyNineSource(app) -> {
                            offers99++
                            ninetyNine.offers++
                            if (price > 0) {
                                ninetyNine.sumPrice += price
                                ninetyNine.countPrice++
                            }
                            if (distance > 0) {
                                ninetyNine.sumDistance += distance
                                ninetyNine.countDistance++
                            }
                            if (time > 0) {
                                ninetyNine.sumTime += time
                                ninetyNine.countTime++
                            }
                            if (pricePerKm > 0) {
                                ninetyNine.sumPricePerKm += pricePerKm
                                ninetyNine.countPricePerKm++
                            }
                        }
                    }
                }

                        val stats = RideOfferStats(
                    totalOffersToday = snapshot.size(),
                    offersUber = offersUber,
                    offers99 = offers99,
                    avgPrice = average(all.sumPrice, all.countPrice),
                    avgPricePerKm = average(all.sumPricePerKm, all.countPricePerKm),
                    avgDistanceKm = average(all.sumDistance, all.countDistance),
                    avgEstimatedTimeMin = average(all.sumTime, all.countTime),
                    avgPriceUber = average(uber.sumPrice, uber.countPrice),
                    avgPrice99 = average(ninetyNine.sumPrice, ninetyNine.countPrice),
                    avgPricePerKmUber = average(uber.sumPricePerKm, uber.countPricePerKm),
                    avgPricePerKm99 = average(ninetyNine.sumPricePerKm, ninetyNine.countPricePerKm),
                    avgDistanceKmUber = average(uber.sumDistance, uber.countDistance),
                    avgDistanceKm99 = average(ninetyNine.sumDistance, ninetyNine.countDistance),
                    avgEstimatedTimeMinUber = average(uber.sumTime, uber.countTime),
                    avgEstimatedTimeMin99 = average(ninetyNine.sumTime, ninetyNine.countTime),
                    offersLast1h = offersLast1h,
                    offersLast3h = offersLast3h,
                    bestPricePerKm = allPricePerKmValues.maxOrNull() ?: 0.0,
                    worstPricePerKm = allPricePerKmValues.minOrNull() ?: 0.0,
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
                val todayStart = todayStartMillis()

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
                            val appSource = resolveAppSource(app)

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

    private fun dayKeyFromTimestamp(timestamp: Long = System.currentTimeMillis()): String {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = timestamp
        }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", year, month, day)
    }

    private fun todayStartMillis(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun isUberSource(appName: String): Boolean =
        appName.contains("uber", ignoreCase = true)

    private fun isNinetyNineSource(appName: String): Boolean =
        appName.contains("99", ignoreCase = true)

    private fun resolveAppSource(appName: String): AppSource = when {
        isUberSource(appName) -> AppSource.UBER
        isNinetyNineSource(appName) -> AppSource.NINETY_NINE
        else -> AppSource.UNKNOWN
    }

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

    data class NeighborhoodDemandMini(
        val neighborhood: String,
        val offersLast15m: Int,
        val offersUberLast15m: Int,
        val offers99Last15m: Int,
        val activeDriversLast15m: Int
    )

    data class CityDemandMini(
        val city: String,
        val offersLast15m: Int,
        val offersUberLast15m: Int,
        val offers99Last15m: Int,
        val activeDriversLast15m: Int,
        val neighborhoods: List<NeighborhoodDemandMini>
    )

    /**
     * Carrega resumo de demanda por cidade/bairro com base em:
     * - ofertas agregadas no bucket atual
     * - motoristas online em tempo real (driver_locations)
     */
    fun loadCityDemandMini(
        cities: List<String>,
        onResult: (List<CityDemandMini>) -> Unit
    ) {
        val currentBucketStart = currentDemandBucketStart()
        val expiryThreshold = System.currentTimeMillis() - DRIVER_LOCATION_EXPIRY_MS

        db.collection(COLLECTION_REGIONAL_DEMAND_15M)
            .whereEqualTo("bucketStart", currentBucketStart)
            .get()
            .addOnSuccessListener { demandSnapshot ->
                data class Totals(
                    var total: Int = 0,
                    var uber: Int = 0,
                    var ninetyNine: Int = 0,
                    var activeDrivers: Int = 0
                )

                val cityTotals = mutableMapOf<String, Totals>()
                val neighborhoodTotalsByCity = mutableMapOf<String, MutableMap<String, Totals>>()

                for (doc in demandSnapshot.documents) {
                    val city = doc.getString("city")?.trim().orEmpty()
                    if (city.isBlank()) continue

                    val total = doc.getLong("offersTotal")?.toInt() ?: 0
                    val uber = doc.getLong("offersUber")?.toInt() ?: 0
                    val ninetyNine = doc.getLong("offers99")?.toInt() ?: 0
                    val activeDrivers = doc.getLong("activeDrivers")?.toInt() ?: 0

                    val neighborhood = doc.getString("neighborhood")?.trim().orEmpty()
                    if (neighborhood.isBlank()) {
                        cityTotals[city] = Totals(
                            total = total,
                            uber = uber,
                            ninetyNine = ninetyNine,
                            activeDrivers = activeDrivers
                        )
                    } else {
                        val cityNeighborhoods = neighborhoodTotalsByCity.getOrPut(city) { mutableMapOf() }
                        cityNeighborhoods[neighborhood] = Totals(
                            total = total,
                            uber = uber,
                            ninetyNine = ninetyNine,
                            activeDrivers = activeDrivers
                        )
                    }
                }

                for ((city, neighborhoodsMap) in neighborhoodTotalsByCity) {
                    if (cityTotals[city] == null) {
                        val aggregated = neighborhoodsMap.values.fold(Totals()) { acc, next ->
                            acc.total += next.total
                            acc.uber += next.uber
                            acc.ninetyNine += next.ninetyNine
                            acc.activeDrivers += next.activeDrivers
                            acc
                        }
                        cityTotals[city] = aggregated
                    }
                }

                db.collection(COLLECTION_DRIVER_LOCATIONS)
                    .whereGreaterThanOrEqualTo("updatedAt", expiryThreshold)
                    .get()
                    .addOnSuccessListener { onlineSnapshot ->
                        val onlineCityDrivers = mutableMapOf<String, Int>()
                        val onlineNeighborhoodDriversByCity = mutableMapOf<String, MutableMap<String, Int>>()

                        for (doc in onlineSnapshot.documents) {
                            val city = doc.getString("city")?.trim().orEmpty()
                            if (city.isBlank()) continue

                            onlineCityDrivers[city] = (onlineCityDrivers[city] ?: 0) + 1

                            val neighborhood = doc.getString("neighborhood")?.trim().orEmpty()
                            if (neighborhood.isNotBlank()) {
                                val neighborhoodsMap = onlineNeighborhoodDriversByCity.getOrPut(city) { mutableMapOf() }
                                neighborhoodsMap[neighborhood] = (neighborhoodsMap[neighborhood] ?: 0) + 1
                            }
                        }

                        val allCities = (
                            cities +
                                cityTotals.keys +
                                neighborhoodTotalsByCity.keys +
                                onlineCityDrivers.keys +
                                onlineNeighborhoodDriversByCity.keys
                            )
                            .filter { it.isNotBlank() }
                            .distinct()

                        val result = allCities
                            .map { city ->
                                val cityTotalsValue = cityTotals[city] ?: Totals()
                                val cityOnlineDrivers = onlineCityDrivers[city] ?: 0

                                val demandNeighborhoods = neighborhoodTotalsByCity[city] ?: emptyMap()
                                val onlineNeighborhoods = onlineNeighborhoodDriversByCity[city] ?: emptyMap()

                                val allNeighborhoodNames = (demandNeighborhoods.keys + onlineNeighborhoods.keys)
                                    .filter { it.isNotBlank() }
                                    .distinct()

                                val neighborhoods = allNeighborhoodNames
                                    .map { neighborhoodName ->
                                        val demandTotals = demandNeighborhoods[neighborhoodName] ?: Totals()
                                        NeighborhoodDemandMini(
                                            neighborhood = neighborhoodName,
                                            offersLast15m = demandTotals.total,
                                            offersUberLast15m = demandTotals.uber,
                                            offers99Last15m = demandTotals.ninetyNine,
                                            activeDriversLast15m = onlineNeighborhoods[neighborhoodName] ?: 0
                                        )
                                    }
                                    .sortedWith(
                                        compareByDescending<NeighborhoodDemandMini> { it.offersLast15m }
                                            .thenByDescending { it.activeDriversLast15m }
                                            .thenBy { it.neighborhood }
                                    )

                                CityDemandMini(
                                    city = city,
                                    offersLast15m = cityTotalsValue.total,
                                    offersUberLast15m = cityTotalsValue.uber,
                                    offers99Last15m = cityTotalsValue.ninetyNine,
                                    activeDriversLast15m = cityOnlineDrivers,
                                    neighborhoods = neighborhoods
                                )
                            }
                            .sortedWith(
                                compareByDescending<CityDemandMini> { it.offersLast15m }
                                    .thenByDescending { it.activeDriversLast15m }
                                    .thenBy { it.city }
                            )

                        onResult(result)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Erro ao carregar motoristas online por cidade", e)

                        val allCities = (cities + cityTotals.keys + neighborhoodTotalsByCity.keys)
                            .filter { it.isNotBlank() }
                            .distinct()

                        val result = allCities
                            .map { city ->
                                val cityTotalsValue = cityTotals[city] ?: Totals()
                                val neighborhoods = neighborhoodTotalsByCity[city]
                                    ?.entries
                                    ?.sortedWith(compareByDescending<Map.Entry<String, Totals>> { it.value.total }.thenBy { it.key })
                                    ?.map {
                                        NeighborhoodDemandMini(
                                            neighborhood = it.key,
                                            offersLast15m = it.value.total,
                                            offersUberLast15m = it.value.uber,
                                            offers99Last15m = it.value.ninetyNine,
                                            activeDriversLast15m = it.value.activeDrivers
                                        )
                                    } ?: emptyList()

                                CityDemandMini(
                                    city = city,
                                    offersLast15m = cityTotalsValue.total,
                                    offersUberLast15m = cityTotalsValue.uber,
                                    offers99Last15m = cityTotalsValue.ninetyNine,
                                    activeDriversLast15m = cityTotalsValue.activeDrivers,
                                    neighborhoods = neighborhoods
                                )
                            }
                            .sortedWith(compareByDescending<CityDemandMini> { it.offersLast15m }.thenBy { it.city })

                        onResult(result)
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Erro ao carregar mini cards de demanda por cidade", e)
                onResult(
                    cities
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()
                        .map {
                            CityDemandMini(
                                city = it,
                                offersLast15m = 0,
                                offersUberLast15m = 0,
                                offers99Last15m = 0,
                                activeDriversLast15m = 0,
                                neighborhoods = emptyList()
                            )
                        }
                )
            }
    }

    private fun currentDemandBucketStart(now: Long = System.currentTimeMillis()): Long {
        return now - (now % DEMAND_BUCKET_10M_MS)
    }

    private fun demandDocId(city: String, neighborhood: String?): String {
        val cityPart = sanitizeDocIdPart(city)
        val neighborhoodPart = sanitizeDocIdPart(neighborhood ?: "")
        return if (neighborhoodPart.isBlank()) "city_${cityPart}" else "city_${cityPart}__bairro_${neighborhoodPart}"
    }

    private fun sanitizeDocIdPart(raw: String): String {
        return raw
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun updateRegionalDemand15m(
        city: String,
        neighborhood: String?,
        appSource: String,
        driverId: String
    ) {
        val bucketStart = currentDemandBucketStart()
        val cityRef = db.collection(COLLECTION_REGIONAL_DEMAND_15M)
            .document(demandDocId(city, null))
        val neighborhoodRef = neighborhood
            ?.takeIf { it.isNotBlank() }
            ?.let { bairro ->
                db.collection(COLLECTION_REGIONAL_DEMAND_15M)
                    .document(demandDocId(city, bairro))
            }

        val isUber = isUberSource(appSource)
        val isNinetyNine = isNinetyNineSource(appSource)
        val now = System.currentTimeMillis()

        db.runTransaction { transaction ->
            fun upsertCounter(docRef: com.google.firebase.firestore.DocumentReference, neighborhoodValue: String?) {
                val snapshot = transaction.get(docRef)
                val existingBucket = snapshot.getLong("bucketStart") ?: -1L

                val baseTotal = if (existingBucket == bucketStart) (snapshot.getLong("offersTotal") ?: 0L) else 0L
                val baseUber = if (existingBucket == bucketStart) (snapshot.getLong("offersUber") ?: 0L) else 0L
                val base99 = if (existingBucket == bucketStart) (snapshot.getLong("offers99") ?: 0L) else 0L
                val baseDriverIds = if (existingBucket == bucketStart) {
                    (snapshot.get("activeDriverIds") as? List<*>) ?: emptyList<Any>()
                } else {
                    emptyList<Any>()
                }

                val updatedDriverIds = baseDriverIds
                    .mapNotNull { it?.toString() }
                    .toMutableSet()
                    .apply {
                        if (driverId.isNotBlank()) add(driverId)
                    }

                val activeDrivers = updatedDriverIds.size.toLong()

                val updated = hashMapOf<String, Any>(
                    "city" to city,
                    "neighborhood" to (neighborhoodValue ?: ""),
                    "bucketStart" to bucketStart,
                    "updatedAt" to now,
                    "offersTotal" to (baseTotal + 1L),
                    "offersUber" to (baseUber + if (isUber) 1L else 0L),
                    "offers99" to (base99 + if (isNinetyNine) 1L else 0L),
                    "activeDrivers" to activeDrivers,
                    "activeDriverIds" to updatedDriverIds.toList()
                )

                transaction.set(docRef, updated, SetOptions.merge())
            }

            upsertCounter(cityRef, null)
            neighborhoodRef?.let { ref -> upsertCounter(ref, neighborhood) }
            null
        }
            .addOnFailureListener { e ->
                Log.e(TAG, "Erro ao atualizar agregados regionais de 10min", e)
            }
    }

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
                    .addOnSuccessListener {
                        Log.d(TAG, "Localização salva: $city / $neighborhood")

                        loadOnlineDriversForCity(city) { cityOnlineCount ->
                            loadOnlineDriversForCity(city, neighborhood) { neighborhoodOnlineCount ->
                                saveOnlineAnalyticsLog(
                                    uid = uid,
                                    city = city,
                                    neighborhood = neighborhood,
                                    cityOnlineCount = cityOnlineCount,
                                    neighborhoodOnlineCount = neighborhoodOnlineCount
                                )
                            }
                        }
                    }
                    .addOnFailureListener { Log.e(TAG, "Erro ao salvar localização", it) }
            },
            onError = { e ->
                Log.e(TAG, "Falha de autenticação ao salvar localização", e)
            }
        )
    }

    private fun saveOnlineAnalyticsLog(
        uid: String,
        city: String,
        neighborhood: String?,
        cityOnlineCount: Int,
        neighborhoodOnlineCount: Int
    ) {
        val timestamp = System.currentTimeMillis()
        val data = hashMapOf(
            "uid" to uid,
            "timestamp" to timestamp,
            "dateKey" to dayKeyFromTimestamp(timestamp),
            "city" to city,
            "neighborhood" to (neighborhood ?: ""),
            "onlineStatus" to "online",
            "onlineDriversCity" to cityOnlineCount,
            "onlineDriversNeighborhood" to neighborhoodOnlineCount,
            "createdAt" to timestamp
        )

        db.collection(COLLECTION_ANALYTICS_ONLINE_LOGS)
            .add(data)
            .addOnFailureListener { e ->
                Log.e(TAG, "Erro ao salvar analytics_online_logs", e)
            }
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
                        isUberSource(app) -> {
                            offersUber++
                            if (price > 0) uberPrices.add(price)
                        }
                        isNinetyNineSource(app) -> {
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

    /**
     * Salva análise diária do motorista em coleção separada para BI/analytics futuro.
     * Usa DemandTracker + stats de ofertas do Firebase como base.
     */
    fun saveDriverDailyDemandAnalytics(
        demandStats: DemandTracker.DemandStats,
        city: String?,
        neighborhood: String?,
        onlineStartMs: Long,
        onlineEndMs: Long = System.currentTimeMillis()
    ) {
        ensureAuthenticated(
            onReady = { uid ->
                val safeStart = onlineStartMs.takeIf { it > 0 } ?: onlineEndMs
                val durationMs = (onlineEndMs - safeStart).coerceAtLeast(0L)
                val durationMin = durationMs / (60 * 1000)
                val dateKey = dayKeyFromTimestamp(onlineEndMs)
                val docId = "${uid}_$dateKey"
                val regionLabel = listOfNotNull(city?.takeIf { it.isNotBlank() }, neighborhood?.takeIf { it.isNotBlank() })
                    .joinToString(" / ")

                loadTodayRideOfferStats { firebaseStats ->
                    db.runTransaction { transaction ->
                        val ref = db.collection(COLLECTION_ANALYTICS_DRIVER_DAILY).document(docId)
                        val snapshot = transaction.get(ref)

                        val previousTotalOnlineMin = snapshot.getLong("totalOnlineDurationMin") ?: 0L
                        val previousSessionCount = snapshot.getLong("sessionCount") ?: 0L
                        val previousRegions = (snapshot.get("regionsVisited") as? List<*>)
                            ?.mapNotNull { it?.toString() }
                            ?.toMutableSet()
                            ?: mutableSetOf()

                        if (regionLabel.isNotBlank()) previousRegions.add(regionLabel)

                        val data = hashMapOf<String, Any>(
                            "uid" to uid,
                            "dateKey" to dateKey,
                            "updatedAt" to System.currentTimeMillis(),
                            "sessionCount" to (previousSessionCount + 1L),
                            "totalOnlineDurationMin" to (previousTotalOnlineMin + durationMin),
                            "lastOnlineStartMs" to safeStart,
                            "lastOnlineEndMs" to onlineEndMs,
                            "lastOnlineDurationMin" to durationMin,
                            "lastCity" to (city ?: ""),
                            "lastNeighborhood" to (neighborhood ?: ""),
                            "regionsVisited" to previousRegions.toList(),

                            "demandTrend" to demandStats.trend.name,
                            "priceTrend" to demandStats.priceTrend.name,
                            "demandLevel" to demandStats.demandLevel.name,
                            "ridesLast15Min" to demandStats.ridesLast15Min,
                            "ridesLast30Min" to demandStats.ridesLast30Min,
                            "ridesLastHour" to demandStats.ridesLastHour,
                            "ridesPreviousHour" to demandStats.ridesPreviousHour,
                            "ridesPerHour" to demandStats.ridesPerHour,
                            "sessionDurationMin" to demandStats.sessionDurationMin,
                            "sessionTotalEarnings" to demandStats.sessionTotalEarnings,
                            "sessionAvgEarningsPerHour" to demandStats.sessionAvgEarningsPerHour,
                            "acceptedRidesTotal" to demandStats.acceptedRidesTotal,
                            "acceptedRidesUber" to demandStats.acceptedRidesUber,
                            "acceptedRides99" to demandStats.acceptedRides99,
                            "acceptedBelowAverage" to demandStats.acceptedBelowAverage,

                            "firebaseTotalOffersToday" to firebaseStats.totalOffersToday,
                            "firebaseOffersLast1h" to firebaseStats.offersLast1h,
                            "firebaseOffersLast3h" to firebaseStats.offersLast3h,
                            "firebaseOffersUber" to firebaseStats.offersUber,
                            "firebaseOffers99" to firebaseStats.offers99,
                            "firebaseAvgPriceUber" to firebaseStats.avgPriceUber,
                            "firebaseAvgPrice99" to firebaseStats.avgPrice99,
                            "firebaseAvgPricePerKmUber" to firebaseStats.avgPricePerKmUber,
                            "firebaseAvgPricePerKm99" to firebaseStats.avgPricePerKm99,
                            "firebaseLastOfferTimestamp" to firebaseStats.lastOfferTimestamp
                        )

                        transaction.set(ref, data, SetOptions.merge())
                        null
                    }
                        .addOnSuccessListener {
                            Log.d(TAG, "Análise diária salva em analytics_driver_daily para $uid/$dateKey")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Erro ao salvar analytics_driver_daily", e)
                        }
                }
            },
            onError = { e ->
                Log.e(TAG, "Falha de autenticação ao salvar analytics diário", e)
            }
        )
    }
}
