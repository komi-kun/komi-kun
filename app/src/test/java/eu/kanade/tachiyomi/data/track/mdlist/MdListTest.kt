package eu.kanade.tachiyomi.data.track.mdlist

import android.content.Context
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALOAuth
import exh.md.network.MangaDexSessionNotifier
import exh.md.utils.MdUtil
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.verify
import mihon.app.di.AppGraph
import mihon.core.metro.GraphProvider
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.IOException

/**
 * Runs the real [MdList.logout], which [exh.md.network.MangaDexLoginHelperTest] stubs.
 *
 * Constructing [MdList] only resolves [TrackPreferences] from Injekt (its interceptor reads the stored token); the
 * other [eu.kanade.tachiyomi.data.track.BaseTracker] dependencies and the notifier's Application are resolved on
 * first use, which logout never reaches. So a fresh Injekt scope holding [TrackPreferences] is enough.
 */
class MdListTest {

    private val tokenPreference = InMemoryPreference("track_token", null, "")
    private val usernamePreference = InMemoryPreference("track_username", null, "")
    private val passwordPreference = InMemoryPreference("track_password", null, "")
    private val authExpiredPreference = InMemoryPreference("track_auth_expired", null, false)

    // InMemoryPreferenceStore returns a new copy of a preference on each lookup, so values set through it are
    // never read back: hand out the same preferences instead, while setCredentials still runs for real.
    private val trackPreferences = spyk(TrackPreferences(InMemoryPreferenceStore())) {
        every { trackToken(any()) } returns tokenPreference
        every { trackUsername(any()) } returns usernamePreference
        every { trackPassword(any()) } returns passwordPreference
        every { trackAuthExpired(any()) } returns authExpiredPreference
    }

    private lateinit var previousInjekt: InjektScope
    private lateinit var mdList: MdList

    @BeforeEach
    fun setUp() {
        previousInjekt = Injekt
        // KUN -->
        // The fork's BaseTracker resolves its preferences from the Metro graph
        // (`Injekt.get<Context>().appGraph.trackPreferences`), so the scope has to provide an app graph.
        val appGraph = mockk<AppGraph>(relaxed = true)
        every { appGraph.trackPreferences } returns trackPreferences
        val context = mockk<Context>(moreInterfaces = arrayOf(GraphProvider::class))
        every { context.applicationContext } returns context
        every { (context as GraphProvider<AppGraph>).graph } returns appGraph
        // KUN <--
        Injekt = InjektScope(DefaultRegistrar()).apply {
            addSingleton(trackPreferences)
            // KUN -->
            addSingleton(context)
            // KUN <--
        }
        // The real notifier needs an Application and the Android notification APIs; only its use is checked here.
        mockkConstructor(MangaDexSessionNotifier::class)
        every { anyConstructed<MangaDexSessionNotifier>().dismiss() } just runs
        every { anyConstructed<MangaDexSessionNotifier>().notifySessionExpired() } just runs

        mdList = MdList(TrackerManager.MDLIST)
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(MangaDexSessionNotifier::class)
        Injekt = previousInjekt
    }

    @Test
    fun `logout clears the interceptor credentials, deletes the stored token, resets the expired flag and dismisses the notification`() {
        mdList.saveCredentials("user", "password")
        mdList.interceptor.setAuth(
            MALOAuth(
                refreshToken = "refresh-token",
                accessToken = "access-token",
                expiresIn = 900,
                createdAt = System.currentTimeMillis() / 1000,
            ),
        )
        // As if a refresh had been rejected earlier, so logout has to clear the flag.
        authExpiredPreference.set(true)
        mdList.isLoggedIn shouldBe true
        sendThroughInterceptor().header("Authorization") shouldBe "Bearer access-token"

        mdList.logout()

        // Stored credentials.
        tokenPreference.isSet() shouldBe false
        MdUtil.loadOAuth(trackPreferences, mdList) shouldBe null
        mdList.isLoggedIn shouldBe false
        mdList.getUsername() shouldBe ""
        mdList.getPassword() shouldBe ""
        authExpiredPreference.get() shouldBe false
        // In-memory credentials: requests are no longer authorized with the old token.
        mdList.interceptor.token shouldBe null
        sendThroughInterceptor().header("Authorization") shouldBe null

        verify(exactly = 1) { anyConstructed<MangaDexSessionNotifier>().dismiss() }
    }

    @Test
    fun `a rejected refresh through the real interceptor fires the log-in-again notification`() {
        // An access token that is expired, so the interceptor has to refresh it before sending anything;
        // MdList wires the interceptor's `onSessionRejected` to the notifier, which is the only "log in
        // again" signal the user gets.
        mdList.interceptor.setAuth(
            MALOAuth(
                refreshToken = "refresh-token",
                accessToken = "expired-access-token",
                expiresIn = 900,
                createdAt = System.currentTimeMillis() / 1000 - 3600,
            ),
        )

        val failure = shouldThrow<IOException> { mdList.interceptor.intercept(rejectingRefreshChain()) }

        failure.message shouldBe "MangaDex session expired, please log in again"
        // The credentials are cleared by the rejection...
        mdList.interceptor.token shouldBe null
        tokenPreference.isSet() shouldBe false
        // ...and the user is told, once.
        verify(exactly = 1) { anyConstructed<MangaDexSessionNotifier>().notifySessionExpired() }
    }

    /**
     * A chain whose token endpoint answers `invalid_grant`, i.e. the one refresh failure that means the
     * session is dead; every other request would succeed.
     */
    private fun rejectingRefreshChain(): Interceptor.Chain {
        val request = Request.Builder().url(MdUtil.baseUrl + "user/follows/manga").build()
        val chainCall = mockk<Call> { every { isCanceled() } returns false }
        return mockk {
            every { request() } returns request
            every { call() } returns chainCall
            every { proceed(any()) } answers {
                val sent = firstArg<Request>()
                val rejected = sent.url.host == "auth.mangadex.org"
                Response.Builder()
                    .request(sent)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (rejected) 400 else 200)
                    .message(if (rejected) "Bad Request" else "OK")
                    .body(
                        (if (rejected) """{"error":"invalid_grant"}""" else "{}")
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
        }
    }

    /** Sends a request through [MdList.interceptor] and returns it as it reached the network. */
    private fun sendThroughInterceptor(): Request {
        var sent: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(mdList.interceptor)
            .addInterceptor { chain ->
                sent = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody())
                    .build()
            }
            .build()
        client.newCall(Request.Builder().url(MdUtil.baseUrl).build()).execute().close()
        return sent!!
    }
}
