package io.github.duwenji.yorisoi.sanpoguide.diagnostics

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Times each request through the client and hands [record] its outcome: the request, the HTTP
 * status (null when there was no answer), the exception's name when it failed, the milliseconds
 * until the answer began, and the body of a successful answer (read ahead, so the caller still
 * reads it whole).
 */
class TimingInterceptor(
    private val record: (request: Request, status: Int?, error: String?, ms: Long, body: String?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = clock()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            record(request, null, e.javaClass.simpleName, clock() - startedAt, null)
            throw e
        }
        val ms = clock() - startedAt
        if (!response.isSuccessful) {
            record(request, response.code, null, ms, null)
            return response
        }
        val body = try {
            response.peekBody(Long.MAX_VALUE).string()
        } catch (e: IOException) {
            // The answer began but broke off; the caller would fail reading it too.
            record(request, response.code, e.javaClass.simpleName, ms, null)
            response.close()
            throw e
        }
        record(request, response.code, null, ms, body)
        return response
    }
}
