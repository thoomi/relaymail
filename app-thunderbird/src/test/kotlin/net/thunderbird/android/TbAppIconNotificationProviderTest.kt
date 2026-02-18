package net.thunderbird.android

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.android.provider.TbAppIconNotificationProvider
import org.junit.Test

class TbAppIconNotificationProviderTest {
    @Test
    fun `provides correct notification icon`() {
        val provider = TbAppIconNotificationProvider()
        val icon = provider.pushNotificationIcon

        assertThat(icon)
            .isEqualTo(net.thunderbird.android.R.mipmap.ic_launcher_monochrome)
    }
}
