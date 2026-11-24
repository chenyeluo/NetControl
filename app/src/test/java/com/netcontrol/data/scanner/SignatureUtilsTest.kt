package com.netcontrol.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SignatureUtilsTest {

    @Test
    fun singleCertFingerprint_isDeterministic64Hex() {
        val cert = "sample-certificate-bytes".toByteArray()
        val fp1 = SignatureUtils.computeFingerprint(listOf(cert))
        val fp2 = SignatureUtils.computeFingerprint(listOf(cert))

        assertEquals(64, fp1.length)
        assertEquals(fp1, fp2)
    }

    @Test
    fun multiCertFingerprint_isOrderIndependent() {
        val certA = "cert-alpha".toByteArray()
        val certB = "cert-beta".toByteArray()

        val fpAB = SignatureUtils.computeFingerprint(listOf(certA, certB))
        val fpBA = SignatureUtils.computeFingerprint(listOf(certB, certA))

        assertEquals(64, fpAB.length)
        assertEquals(fpAB, fpBA)
        assertNotEquals(fpAB, SignatureUtils.computeFingerprint(listOf(certA)))
    }

    @Test
    fun emptyCertList_returnsEmptyString() {
        assertEquals("", SignatureUtils.computeFingerprint(emptyList()))
        assertEquals("", SignatureUtils.computeFingerprint(listOf(ByteArray(0))))
    }
}
