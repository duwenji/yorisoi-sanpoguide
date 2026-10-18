package io.github.duwenji.yorisoi.sanpoguide.diagnostics

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Reports each request's server, outcome and time until the response began, so a walk's record
 * shows which search servers answered and which gave up. [record] gets the HTTP status, or null
 * and the exception's name when no response came.
 */
class TimingInterceptor(
    private val record: (host: String, status: Int?, error: String?, ms: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val startedAt = clock()
        val response = try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            record(host, null, e.javaClass.simpleName, clock() - startedAt)
            throw e
        }
        record(host, response.code, null, clock() - startedAt)
        return response
    }
}
