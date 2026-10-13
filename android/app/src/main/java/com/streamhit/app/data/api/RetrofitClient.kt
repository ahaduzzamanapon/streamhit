package com.streamhit.app.data.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object RetrofitClient {

    const val BASE_URL = "https://nxly.online/"
    private const val TAG = "RetrofitClient"

    private const val SSL_COM_ROOT_CA_2022 = """-----BEGIN CERTIFICATE-----
MIIFiTCCA3GgAwIBAgIQb77arXO9CEDii02+1PdbkTANBgkqhkiG9w0BAQsFADBO
MQswCQYDVQQGEwJVUzEYMBYGA1UECgwPU1NMIENvcnBvcmF0aW9uMSUwIwYDVQQD
DBxTU0wuY29tIFRMUyBSU0EgUm9vdCBDQSAyMDIyMB4XDTIyMDgyNTE2MzQyMloX
DTQ2MDgxOTE2MzQyMVowTjELMAkGA1UEBhMCVVMxGDAWBgNVBAoMD1NTTCBDb3Jw
b3JhdGlvbjElMCMGA1UEAwwcU1NMLmNvbSBUTFMgUlNBIFJvb3QgQ0EgMjAyMjCC
AiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBANCkCXJPQIgSYT41I57u9nTP
L3tYPc48DRAokC+X94xI2KDYJbFMsBFMF3NQ0CJKY7uB0ylu1bUJPiYYf7ISf5OY
t6/wNr/y7hienDtSxUcZXXTzZGbVXcdotL8bHAajvI9AI7YexoS9UcQbOcGV0ins
S657Lb85/bRi3pZ7QcacoOAGcvvwB5cJOYF0r/c0WRFXCsJbwST0MXMwgsadugL3
PnxEX4MN8/HdIGkWCVDi1FW24IBydm5MR7d1VVm0U3TZlMZBrViKMWYPHqIbKUBO
L9975hYsLfy/7PO0+r4Y9ptJ1O4Fbtk085zx7AGL0SDGD6C1vBdOSHtRwvzpXGk3
R2azaPgVKPC506QVzFpPulJwoxJF3ca6TvvC0PeoUidtbnm1jPx7jMEWTO6Af77w
dr5BUxIzrlo4QqvXDz5BjXYHMtWrifZOZ9mxQnUjbvPNQrL8VfVThxc7wDNY8VLS
+YCk8OjwO4s4zKTGkH8PnP2L0aPP2oOnaclQNtVcBdIKQXTbYxE3waWglksejBYS
d66UNHsef8JmAOSqg+qKkK3ONkRN0VHpvB/zagX9wHQfJRlAUW7qglFA35u5CCoG
AtUjHBPW6dvbxrB6y3snm/vg1UYk7RBLY0ulBY+6uB0rpvqR4pJSvezrZ5dtmi2f
gTIFZzL7SAg/2SW4BCUvAgMBAAGjYzBhMA8GA1UdEwEB/wQFMAMBAf8wHwYDVR0j
BBgwFoAU+y437uOEeicuzRk1sTN8/9REQrkwHQYDVR0OBBYEFPsuN+7jhHonLs0Z
NbEzfP/UREK5MA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG9w0BAQsFAAOCAgEAjYlt
hEUY8U+zoO9opMAdrDC8Z2awms22qyIZZtM7QbUQnRC6cm4pJCAcAZli05bg4vsM
QtfhWsSWTVTNj8pDU/0quOr4ZcoBwq1gaAafORpR2eCNJvkLTqVTJXojpBzOCBvf
R4iyrT7gJ4eLSYwfqUdYe5byiB0YrrPRpgqU+tvT5TgKa3kSM/tKWTcWQA673vWJ
DPFs0/dRa1419dvAJuoSc06pkZCmF8NsLzjUo3KUQyxi4U5cMj29TH0ZR6LDSeeW
P4+a0zvkEdiLA9z2tmBVGKaBUfPhqBVq6+AL8BQx1rmMRTqoENjwuSfr98t67wVy
lrXEj5ZzxOhWc5y8aVFjvO9nHEMaX3cZHxj4HCUp+UmZKbaSPaKDN7EgkaibMOlq
bLQjk2UEqxHzDh1TJElTHaE/nUiSEeJ9DU/1172iWD54nR4fK/4huxoTtrEoZP2w
AgDHbICivRZQIA9ygV/MlP+7mea6kMvq+cYMwq7FGc4zoWtcu358NFcXrfA/rs3q
r5nsLFR+jM4uElZI7xc7P0peYNLcdDa8pUNjyw9bowJWCZ4kLOGGgYz+qxcs+sji
Mho6/4UIyYOf8kpIEFR3N+2ivEC+5BB09+Rbu7nzifmPQdjH5FCQNYA+HLhNkNPU
98OwoX6EyneSMSy4kLGCenROmxMmtNVQZlR4rmA=
-----END CERTIFICATE-----"""

    private const val SSL_COM_INTERMEDIATE_R1 = """-----BEGIN CERTIFICATE-----
MIIGAjCCA+qgAwIBAgIQJrn/5Flvph4a8hxj1nJ0ijANBgkqhkiG9w0BAQsFADBOMQswCQYDVQQG
EwJVUzEYMBYGA1UECgwPU1NMIENvcnBvcmF0aW9uMSUwIwYDVQQDDBxTU0wuY29tIFRMUyBSU0Eg
Um9vdCBDQSAyMDIyMB4XDTIyMTAyMTE3MDIwM1oXDTMyMTAxODE3MDIwMlowTzELMAkGA1UEBhMC
VVMxGDAWBgNVBAoMD1NTTCBDb3Jwb3JhdGlvbjEmMCQGA1UEAwwdU1NMLmNvbSBUTFMgSXNzdWlu
ZyBSU0EgQ0EgUjEwggGiMA0GCSqGSIb3DQEBAQUAA4IBjwAwggGKAoIBgQCsGwIUQNo72SnktXL5
DrWqzRx7JO52R8ymc5bzpZWCPGfU6XcZUQQl4acAlpxbox7f8MSUjen+gCIf5GjN9JpXXGrNdhoU
uvnHDM/cONSJXx7PjVAuHXglktH5ZXpVw19hjRfUG2a+CZuOEgKnJzL/0NGJtREtqfrNy6KyoDz0
H/a9VidAb4AImHiZOLuRaCBZPn+9Ugj9TWTFTEfdvcX0or8vFnGaFw1lt6pTrnkstQH9aqYBx+d4
CDjVkNaITaoJLbG8HJo1lmZS+RZ2VZZTGn28Wg5DdNyplWxzBVMcv+zhu1EKIlqSGuvHnXor2iFR
FjBy1bVdi7sR+Vou4PUaw/EQH4vLRYHHM2rUv/Z5qHcjDxfF+CXHrejES0PaYaA/ELJufScN/Ixi
kaIORWuBIUrr34pcLj+bFTlyr5CWGFbJFJeWsz5CQFxR3JJa1mkzfHHZiE1JZgojzaLYzKxztH2I
bivPnMmMlUhLtuIW7h1JNGNeBD9b7pGd+IXPzhsCAwEAAaOCAVkwggFVMBIGA1UdEwEB/wQIMAYB
Af8CAQAwHwYDVR0jBBgwFoAU+y437uOEeicuzRk1sTN8/9REQrkwTAYIKwYBBQUHAQEEQDA+MDwG
CCsGAQUFBzAChjBodHRwOi8vY2VydC5zc2wuY29tL1NTTGNvbS1UTFMtUm9vdC0yMDIyLVJTQS5j
ZXIwPwYDVR0gBDgwNjA0BgRVHSAAMCwwKgYIKwYBBQUHAgEWHmh0dHBzOi8vd3d3LnNzbC5jb20v
cmVwb3NpdG9yeTAdBgNVHSUEFjAUBggrBgEFBQcDAgYIKwYBBQUHAwEwQQYDVR0fBDowODA2oDSg
MoYwaHR0cDovL2NybHMuc3NsLmNvbS9TU0xjb20tVExTLVJvb3QtMjAyMi1SU0EuY3JsMB0GA1Ud
DgQWBBR5upR3oA0Z3TTmOaT8TKXSWm31jDAOBgNVHQ8BAf8EBAMCAYYwDQYJKoZIhvcNAQELBQAD
ggIBAKZ9PtxW2JsKR/yncBvfHA05VtQ0kQEqCVSz2A3X371wf2cI+/aTFaFauBguePcTdIPcYqo7
FFJ9GHCc9lN6IY9UEFCUkjtAYJ/J3FqaS80OGqdp8bqdbeg2vmpV6T8RNj1aaEjOYcOvlzhXp8sg
xzEgvSaiUklnx4A48vPnVQ/c+QsJ7SsFd1nzSd/+FtAiPRKKJsjLp0EC147g6Z3KHj4ymdiz3wlO
+affUyfOeqrP+DNcMf7QuuCcNAzYExX6tYxWXdvIs/+2QyH0s3bSnCVl30IIOrbiZlSSqXQtKYv2
/Yc/M1Ws0nTUmprWdexrxlkkh4X8v9mqiQvMMzsB/pTlHICJvu4bE6pdGG8hqlTryml2tefaMbcP
adQIlZfOOHEiTxfEEYghbj3dhi+B1cb1W6TfstH0iRnL2BZqH1X2NB2XL27lwptGkK+Pi4/edbgY
3ZQM4Ylbq+zewYOeUn493lM97h/IApNisM6R4N1o61PLnJ88AYKI5NfhhbZAHmDoadbzKwjchqkX
fjXtTH57ADcqEw9lfQOgtqhV11c7AAYo1uqJflCVwoEjkUb1P2381YCDDdnq78XOQWPLYccZoumv
kAD5BXj1+etEwNgrK21W5wi5ZCfJLHhAxUvNxRAwwUswhbVNY/9yxUltpaXsutug019GSlBIiTZi
nEPk
-----END CERTIFICATE-----"""

    fun getCustomSslSocketFactory(): Pair<javax.net.ssl.SSLSocketFactory, X509TrustManager>? {
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val rootCert = cf.generateCertificate(
                ByteArrayInputStream(SSL_COM_ROOT_CA_2022.toByteArray(Charsets.UTF_8))
            ) as X509Certificate
            val interCert = cf.generateCertificate(
                ByteArrayInputStream(SSL_COM_INTERMEDIATE_R1.toByteArray(Charsets.UTF_8))
            ) as X509Certificate

            // 1. Load system default trust managers
            val defaultTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }
            val systemTrustManager = defaultTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()

            // 2. Load custom trust manager with SSL.com 2022 certificates
            val customKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("ssl_com_root_2022", rootCert)
                setCertificateEntry("ssl_com_intermediate_r1", interCert)
            }
            val customTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(customKeyStore)
            }
            val customTrustManager = customTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()

            val compositeTrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    try {
                        systemTrustManager?.checkClientTrusted(chain, authType)
                    } catch (e: Exception) {
                        customTrustManager?.checkClientTrusted(chain, authType) ?: throw e
                    }
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    try {
                        systemTrustManager?.checkServerTrusted(chain, authType)
                    } catch (e: Exception) {
                        try {
                            customTrustManager?.checkServerTrusted(chain, authType)
                        } catch (_: Exception) {
                            throw e
                        }
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    val list = mutableListOf<X509Certificate>()
                    systemTrustManager?.acceptedIssuers?.let { list.addAll(it) }
                    customTrustManager?.acceptedIssuers?.let { list.addAll(it) }
                    return list.toTypedArray()
                }
            }

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(compositeTrustManager), SecureRandom())
            }
            Pair(sslContext.socketFactory, compositeTrustManager)
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring SSLSocketFactory", e)
            null
        }
    }

    private fun safeHeader(builder: okhttp3.Request.Builder, name: String, value: String?) {
        if (value.isNullOrBlank()) return
        try {
            val sanitized = value.filter { it in ' '..'~' }.trim()
            if (sanitized.isNotEmpty()) {
                builder.header(name, sanitized)
            }
        } catch (_: Exception) {}
    }

    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                    .header("User-Agent", "StreamfitMobile/1.0.8 (Android)")
                    .header("Accept", "application/json")

                safeHeader(requestBuilder, "X-App-Device-Id", com.streamhit.app.data.analytics.AppAnalyticsManager.cachedDeviceId)
                safeHeader(requestBuilder, "X-App-Model", com.streamhit.app.data.analytics.AppAnalyticsManager.getDeviceModel())
                safeHeader(requestBuilder, "X-App-OS", com.streamhit.app.data.analytics.AppAnalyticsManager.getOsVersion())
                safeHeader(requestBuilder, "X-App-Version", com.streamhit.app.data.analytics.AppAnalyticsManager.getAppVersion())

                chain.proceed(requestBuilder.build())
            }
            .addInterceptor(logging)

        getCustomSslSocketFactory()?.let { (socketFactory, trustManager) ->
            builder.sslSocketFactory(socketFactory, trustManager)
        }

        builder.build()
    }

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

