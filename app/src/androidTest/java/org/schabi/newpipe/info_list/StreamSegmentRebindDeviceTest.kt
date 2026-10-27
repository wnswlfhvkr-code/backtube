package org.schabi.newpipe.info_list

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.FutureTask
import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ItemStreamSegmentBinding
import org.schabi.newpipe.extractor.stream.StreamSegment

/** Full binds must restore fields changed by an earlier row using the same holder. */
class StreamSegmentRebindDeviceTest {
    @Test
    fun unnamedThenNamedThenUnnamedRestoresTitleLineLimit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val task = FutureTask<Void?> {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.LightTheme)
            val binding = ItemStreamSegmentBinding.inflate(
                LayoutInflater.from(context),
                FrameLayout(context),
                false
            )
            val listener = object : StreamSegmentAdapter.StreamSegmentListener {
                override fun onItemClick(item: StreamSegmentItem, seconds: Int) = Unit
                override fun onItemLongClick(item: StreamSegmentItem, seconds: Int) = Unit
            }

            StreamSegmentItem(segment("Unnamed", null), listener).bind(binding, 0)
            assertEquals(2, binding.textViewTitle.maxLines)
            assertEquals(View.GONE, binding.textViewChannel.visibility)

            StreamSegmentItem(segment("Named", "Channel"), listener).bind(binding, 0)
            assertEquals(1, binding.textViewTitle.maxLines)
            assertEquals(View.VISIBLE, binding.textViewChannel.visibility)
            assertEquals("Channel", binding.textViewChannel.text.toString())

            StreamSegmentItem(segment("Unnamed again", null), listener).bind(binding, 0)
            assertEquals(2, binding.textViewTitle.maxLines)
            assertEquals(View.GONE, binding.textViewChannel.visibility)
            null
        }
        instrumentation.runOnMainSync(task)
        task.get()
    }

    private fun segment(title: String, channel: String?): StreamSegment = StreamSegment("/watch/segment", 10).apply {
        setTitle(title)
        setChannelName(channel)
    }
}
