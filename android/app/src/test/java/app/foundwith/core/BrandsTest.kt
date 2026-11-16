package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandsTest {
    @Test
    fun brandsAreRecognisedFromManufacturerOrBrand() {
        assertEquals(BrandFamily.XIAOMI, Brands.family("Xiaomi", "Redmi"))
        assertEquals(BrandFamily.XIAOMI, Brands.family("POCO", null))
        assertEquals(BrandFamily.VIVO, Brands.family("vivo", "iQOO"))
        assertEquals(BrandFamily.ONEPLUS, Brands.family("OnePlus", "OnePlus"))
        assertEquals(BrandFamily.REALME, Brands.family("realme", "realme"))
        assertEquals(BrandFamily.OPPO, Brands.family("OPPO", "OPPO"))
        assertEquals(BrandFamily.TRANSSION, Brands.family("TECNO MOBILE LIMITED", "TECNO"))
        assertEquals(BrandFamily.TRANSSION, Brands.family("INFINIX MOBILITY LIMITED", "Infinix"))
        assertEquals(BrandFamily.SAMSUNG, Brands.family("samsung", "samsung"))
        assertEquals(BrandFamily.OTHER, Brands.family("motorola", "motorola"))
        assertEquals(BrandFamily.OTHER, Brands.family(null, null))
    }

    @Test
    fun strictBrandsGetTheirOwnFix() {
        assertTrue(Brands.isStrict(BrandFamily.XIAOMI) && Brands.isStrict(BrandFamily.TRANSSION))
        assertFalse(Brands.isStrict(BrandFamily.SAMSUNG) || Brands.isStrict(BrandFamily.OTHER))
        assertEquals("Turn on Autostart", Brands.fix(BrandFamily.XIAOMI).setting)
        assertEquals("Allow background activity", Brands.fix(BrandFamily.REALME).setting)
        assertTrue(Brands.fix(BrandFamily.VIVO).screens.isNotEmpty())
        assertTrue(Brands.fix(BrandFamily.OTHER).screens.isEmpty())
    }

    @Test
    fun reportNameIsPlain() {
        assertEquals("xiaomi", Brands.reportName("Xiaomi"))
        assertEquals("tecno mobile limited", Brands.reportName("TECNO MOBILE LIMITED"))
        assertEquals("unknown", Brands.reportName("  "))
        assertEquals("hmd global", Brands.reportName("HMD Global!"))
    }
}
