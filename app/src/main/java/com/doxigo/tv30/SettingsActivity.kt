package com.doxigo.tv30

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.text.format.Formatter
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale

/**
 * Settings as the Apple TV and Netflix apps lay them out, in three columns that each look like
 * what they are: the five sections down the start side as the rail's pills; the focused
 * section's options on a raised panel, in titled groups, each row its name then its value (a
 * switch, a chip, or plain); and at the end edge, on the ink, the section's icon over what the
 * focused option does, every value it takes and a live preview where there is one. Up and Down
 * stay in a column; toward the end goes into the options, toward the start back out and on into
 * the rail. OK steps a value to the next (a switch flips), so the remote never leaves the row.
 * Back steps out of the options, then leaves for home.
 */
class SettingsActivity : TvActivity() {
    /**
     * One row: its name, what it does, its value now, every value it takes, and what OK does.
     * [on] makes it a switch; [trail] marks an OK that acts rather than steps (clear, open);
     * [note] is what the value means here, worked out live, and [ahead] the share of a film
     * preload's preview lights. [sub] is a second line under the name; [warn] turns the value red.
     */
    private class Option(
        @StringRes val label: Int,
        val help: () -> CharSequence,
        val value: () -> CharSequence? = { null },
        val values: List<CharSequence> = emptyList(),
        val current: () -> Int = { -1 },
        val on: (() -> Boolean)? = null,
        val note: () -> CharSequence? = { null },
        val ahead: (() -> Float)? = null,
        @DrawableRes val icon: Int = 0,
        @DrawableRes val trail: Int = 0,
        val danger: Boolean = false,
        @StringRes val sub: Int = 0,
        val warn: () -> Boolean = { false },
        val onClick: (() -> Unit)? = null,
    )

    /** Rows under a title in the panel; a section's first group may go untitled (0). */
    private class Group(@StringRes val title: Int, val options: List<Option>)

    private class Section(@StringRes val name: Int, @DrawableRes val icon: Int, val about: () -> CharSequence, val groups: () -> List<Group>)

    private val sections = listOf(
        Section(R.string.settings_playback, R.drawable.settings_ic_playback, text(R.string.settings_playback_about), ::playback),
        Section(R.string.settings_subtitles, R.drawable.settings_ic_subtitles, text(R.string.settings_subtitles_about), ::subtitles),
        Section(R.string.settings_app, R.drawable.settings_ic_app, text(R.string.settings_app_about), ::app),
        Section(R.string.settings_services, R.drawable.settings_ic_services, text(R.string.settings_services_about), ::services),
        Section(R.string.settings_account, R.drawable.settings_ic_account, {
            getString(R.string.settings_account_about) + if (Api.loggedIn) " " + getString(R.string.settings_plan_help) else ""
        }, ::account),
    )
    private lateinit var page: ViewGroup
    private lateinit var categories: LinearLayout
    private lateinit var panel: View
    private lateinit var surface: View
    private lateinit var options: LinearLayout
    private lateinit var detail: View
    private lateinit var detailIcon: ImageView
    private lateinit var detailTitle: TextView
    private lateinit var detailBody: TextView
    private lateinit var choices: LinearLayout
    private lateinit var meter: View
    private lateinit var meterFill: View
    private lateinit var note: TextView
    private lateinit var sample: TextView
    /** The focusable rows on the panel now, in order (the panel also holds titles and hairlines). */
    private val rows = mutableListOf<View>()
    /** The section whose options show, and what the end pane describes now. */
    private var section = -1
    private var described: Any? = null
    private val dp by lazy { resources.displayMetrics.density }
    private val rtl by lazy { resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL }
    private val towardStart by lazy { if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT }
    private val towardEnd by lazy { if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT }

    // The disk, measured off the main thread once the page is up (null until then).
    private var imageBytes: Long? = null
    private var videoBytes: Long? = null
    private var videoRoom: Long? = null
    private var readAhead: Long? = null

    private var signingOut = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.rail_screen)
        val content = layoutInflater.inflate(R.layout.settings_page, findViewById(R.id.browse_host), true)
        page = content.findViewById(R.id.settings_page)
        categories = content.findViewById(R.id.settings_categories)
        panel = content.findViewById(R.id.settings_panel)
        surface = content.findViewById(R.id.settings_surface)
        options = content.findViewById(R.id.settings_options)
        detail = content.findViewById(R.id.settings_detail)
        detailIcon = content.findViewById(R.id.settings_detail_icon)
        detailTitle = content.findViewById(R.id.settings_detail_title)
        detailBody = content.findViewById(R.id.settings_detail_body)
        choices = content.findViewById(R.id.settings_choices)
        meter = content.findViewById(R.id.settings_meter)
        meterFill = content.findViewById(R.id.settings_meter_fill)
        note = content.findViewById(R.id.settings_note)
        sample = content.findViewById(R.id.settings_sample)
        content.findViewById<TextView>(R.id.settings_title).let { if (it.text.isLatin) it.typeface = font(R.font.inter_bold) }
        // The lit share grows from the film's start, the start edge in either direction.
        meterFill.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ -> v.pivotX = if (rtl) (right - left).toFloat() else 0f }

        sections.forEachIndexed { i, s -> categories.addView(category(i, s)) }
        stopAtEnds((0 until categories.childCount).map(categories::getChildAt))
        val start = intent.getIntExtra(EXTRA_SECTION, 0)
        show(start)
        Rail(this, Rail.Section.SETTINGS) { categories.getChildAt(section) }.ready()
        (rows.getOrNull(intent.getIntExtra(EXTRA_ROW, -1)) ?: categories.getChildAt(start)).takeFocus()
        rise(listOf(content.findViewById(R.id.settings_title), categories, panel, detail))
        measure()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppUpdates.refresh()
                launch { AppUpdates.state.collect { refresh() } }
                launch {
                    while (isActive) {
                        if (AppUpdates.state.value is AppUpdates.Status.Downloading) AppUpdates.refresh()
                        delay(1_000)
                    }
                }
            }
        }
    }

    /** Back steps out of the options first, then leaves (Esc arrives here as Back, see TvActivity). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK || !options.hasFocus()) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) categories.getChildAt(section).requestFocus()
        return true
    }

    private fun category(index: Int, s: Section): View =
        (layoutInflater.inflate(R.layout.settings_category, categories, false) as TextView).apply {
            id = View.generateViewId()
            text = latin(context, getString(s.name), R.font.inter_medium)
            setCompoundDrawablesRelativeWithIntrinsicBounds(s.icon, 0, 0, 0)
            setOnFocusChangeListener { _, focused ->
                if (!focused) return@setOnFocusChangeListener
                for (i in 0 until categories.childCount) categories.getChildAt(i).isActivated = false
                if (index != section) show(index)
                sample.reveal(false)
                describe(s, getString(s.name), s.about())
            }
            setOnKeyListener { _, code, event ->
                code == towardEnd && event.action == KeyEvent.ACTION_DOWN && rows.firstOrNull()?.requestFocus() == true
            }
        }

    /**
     * Puts section [index]'s options on the panel. Moving between sections they cross-slide (the
     * last ones out one way, these in from the other, as the list of sections runs) while the
     * panel's surface stretches to its new height; not while the remote is racing.
     */
    private fun show(index: Int) {
        val from = section
        section = index
        val slide = from >= 0 && !Motion.fast
        val dir = if (index > from) 1 else -1
        if (slide) leave(-dir) else options.removeAllViews()
        rows.clear()
        if (index == ACCOUNT) {
            options.addView(accountCard())
            options.addView(seam())
        }
        sections[index].groups().forEach { group ->
            if (group.title != 0) options.addView(groupTitle(group.title, first = options.childCount == 0))
            group.options.forEachIndexed { i, option ->
                if (i > 0) options.addView(seam())
                options.addView(row(option).also(rows::add))
            }
        }
        stopAtEnds(rows)
        fit(animate = slide)
        if (!slide) return
        options.alpha = 0f
        options.translationY = Motion.travel(12 * dp * dir)
        options.animate().alpha(1f).translationY(0f).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
    }

    /** The rows on show slide [dir] and fade in the page's overlay, out of the way of the next ones. */
    private fun leave(dir: Int) {
        val overlay = page.overlay
        overlay.clear()
        val travel = Motion.travel(12 * dp * dir)
        for (view in (0 until options.childCount).map(options::getChildAt)) {
            overlay.add(view) // off the panel, where it was on the screen
            view.animate().alpha(0f).translationY(travel).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT)
                .withEndAction { overlay.remove(view) }.start()
        }
    }

    /**
     * Sizes the surface to the rows (a plain view would fill whatever room the page leaves it),
     * stretching it from the last section's height to this one's when [animate]: a transform, not
     * a layout per frame.
     */
    private fun fit(animate: Boolean) {
        val width = resources.getDimensionPixelSize(R.dimen.settings_panel_width)
        options.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val before = surface.layoutParams.height
        val after = options.measuredHeight
        surface.layoutParams.height = after
        surface.requestLayout()
        if (!animate || before <= 0 || after <= 0 || Motion.reduced) return
        surface.pivotY = 0f
        surface.scaleY = before.toFloat() / after
        // Quicker than the rows' fade, so the surface is there before they are.
        surface.animate().scaleY(1f).setInterpolator(Motion.easeOut).setDuration(Motion.EXIT).start()
    }

    private fun groupTitle(@StringRes title: Int, first: Boolean) =
        (layoutInflater.inflate(R.layout.settings_group, options, false) as TextView).apply {
            text = latin(context, getString(title), R.font.inter_medium)
            if (first) setPaddingRelative(paddingStart, (8 * dp).toInt(), paddingEnd, paddingBottom)
        }

    /** A hairline between two rows of a group, inset to their text. */
    private fun seam() = View(this).apply {
        tag = SEAM
        setBackgroundColor(getColor(R.color.hairline))
        val inset = resources.getDimensionPixelSize(R.dimen.settings_row_pad)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp.toInt()).apply {
            marginStart = inset
            marginEnd = inset
        }
    }

    /** A hairline against the white card would rule a line along its edge, so the card's two go. */
    private fun seams() {
        for (i in 0 until options.childCount) {
            val seam = options.getChildAt(i).takeIf { it.tag == SEAM } ?: continue
            val touching = options.getChildAt(i - 1)?.isFocused == true || options.getChildAt(i + 1)?.isFocused == true
            seam.visibility = if (touching) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun row(option: Option): View {
        val row = layoutInflater.inflate(R.layout.settings_row, options, false)
        row.id = View.generateViewId()
        row.tag = option
        val label = row.findViewById<TextView>(R.id.settings_label)
        label.text = latin(this, getString(option.label), R.font.inter_medium)
        label.typeface = medium(label.text)
        if (option.danger) label.setTextColor(getColorStateList(R.color.settings_danger_ink))
        if (option.icon != 0) row.findViewById<ImageView>(R.id.settings_icon).apply {
            setImageResource(option.icon)
            imageTintList = getColorStateList(if (option.danger) R.color.settings_danger_ink else R.color.settings_label)
            visibility = View.VISIBLE
        }
        if (option.trail != 0) row.findViewById<ImageView>(R.id.settings_trail).apply {
            setImageResource(option.trail)
            visibility = View.VISIBLE
        }
        if (option.sub != 0) {
            row.findViewById<TextView>(R.id.settings_sub).apply {
                text = latin(context, getString(option.sub), R.font.inter_regular)
                visibility = View.VISIBLE
            }
            row.layoutParams.height = resources.getDimensionPixelSize(R.dimen.settings_row_tall)
        }
        // A value that isn't one of a set (a size, the version) is read, not chosen: no chip, and
        // centred on the line as the name is, rather than in a pill.
        if (option.values.isEmpty()) row.findViewById<TextView>(R.id.settings_value).apply {
            background = null
            setPaddingRelative(0, 0, 0, 0)
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            includeFontPadding = true
        }
        row.findViewById<View>(R.id.settings_toggle).visibility = if (option.on != null) View.VISIBLE else View.GONE
        bind(row)
        row.setOnFocusChangeListener { _, focused ->
            lift(row, focused)
            seams()
            if (!focused) return@setOnFocusChangeListener
            categories.getChildAt(section).isActivated = true
            describe(option)
        }
        row.setOnClickListener {
            val click = option.onClick ?: return@setOnClickListener
            click()
            bind(row, changed = true)
            describe(option)
        }
        row.setOnKeyListener { _, code, event ->
            code == towardStart && event.action == KeyEvent.ACTION_DOWN && categories.getChildAt(section).requestFocus()
        }
        return row
    }

    /** The focused row comes forward a little, 8dp short of the panel's edge; the white card says the rest. */
    private fun lift(row: View, focused: Boolean) {
        val scale = if (focused) ROW_LIFT else 1f
        val duration = when {
            Motion.reduced -> 0L
            focused -> Motion.FOCUS_IN
            else -> Motion.FOCUS_OUT
        }
        row.animate().scaleX(scale).scaleY(scale).setInterpolator(Motion.easeOut)
            .setDuration(if (Motion.fast) duration / 2 else duration).start()
    }

    /**
     * Shows [row]'s value now. A [changed] switch slides its knob across; a [changed] value slides
     * in from the end, as if the next one had come round.
     */
    private fun bind(row: View, changed: Boolean = false) {
        val option = row.tag as Option
        val animate = changed && !Motion.fast
        val on = option.on
        if (on != null) {
            row.isActivated = on()
            val thumb = row.findViewById<View>(R.id.settings_thumb)
            val x = if (on()) resources.getDimension(R.dimen.settings_toggle_travel) * if (rtl) -1 else 1 else 0f
            if (animate && !Motion.reduced) thumb.animate().translationX(x).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
            else thumb.translationX = x
            row.contentDescription = dotted(getString(option.label), onOff(on()))
            return
        }
        val value = row.findViewById<TextView>(R.id.settings_value)
        val text = option.value()
        if (option.values.isEmpty()) value.setTextColor(getColorStateList(if (option.warn()) R.color.settings_danger_ink else R.color.settings_value))
        value.text = text?.let { latin(this, it, R.font.inter_medium) }
        value.typeface = medium(text ?: "")
        // In a chip, Persian comes down onto the pill's middle (see settings_chip_nudge).
        if (option.values.isNotEmpty()) value.setPaddingRelative(value.paddingStart, nudge(text ?: ""), value.paddingEnd, 0)
        value.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        row.contentDescription = dotted(getString(option.label), text?.toString())
        if (!animate) return
        value.alpha = 0f
        value.translationX = Motion.travel(8 * dp * if (rtl) -1 else 1)
        value.animate().alpha(1f).translationX(0f).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
    }

    private fun describe(option: Option) {
        describe(option, getString(option.label), option.help(), option.values, option.current(), option.note())
        val share = option.ahead?.invoke()
        if (share != null && meter.visibility == View.VISIBLE && !Motion.reduced) {
            meterFill.animate().scaleX(share).setInterpolator(Motion.easeOut).setDuration(Motion.ENTER).start()
        } else if (share != null) {
            meterFill.scaleX = share
        }
        meter.visibility = if (share != null) View.VISIBLE else View.GONE
        val subtitles = section == SUBTITLES
        if (subtitles) styleSample()
        sample.reveal(subtitles)
    }

    /**
     * The end pane: the section's icon, what [about] is and does, its values with the one now
     * checked, and [now] (what that value means here). It fades across only when [about] changes.
     */
    private fun describe(about: Any, title: CharSequence, body: CharSequence, values: List<CharSequence> = emptyList(), current: Int = -1, now: CharSequence? = null) {
        detailIcon.setImageResource(sections[section].icon)
        detailTitle.text = latin(this, title, R.font.inter_bold)
        detailBody.text = latin(this, body, R.font.inter_regular)
        choices.removeAllViews()
        values.forEachIndexed { i, name -> choices.addView(choice(name, i == current)) }
        choices.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
        note.text = now?.let { latin(this, it, R.font.inter_regular) }
        note.visibility = if (now.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (about !is Option) meter.visibility = View.GONE
        if (about === described) return
        described = about
        if (Motion.fast) return
        detail.alpha = 0.3f
        detail.animate().alpha(1f).setInterpolator(Motion.easeOut).setDuration(Motion.FOCUS_IN).start()
    }

    /** One of the values in the pane: the one now checked and bright, the rest faint, all lined up past the check's room. */
    private fun choice(name: CharSequence, now: Boolean) = TextView(this).apply {
        setTextAppearance(R.style.TextAppearance_App_Body)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = font(
            when {
                name.isLatin -> if (now) R.font.inter_bold else R.font.inter_regular
                else -> if (now) R.font.vazirmatn_bold else R.font.vazirmatn_regular
            },
        )
        setTextColor(getColor(if (now) R.color.text else R.color.text_faint))
        text = latin(context, name, if (now) R.font.inter_bold else R.font.inter_regular)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        widenSpaces()
        setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
        compoundDrawablePadding = (8 * dp).toInt()
        val size = (16 * dp).toInt()
        val check = getDrawable(R.drawable.player_check)!!.mutate().apply {
            setBounds(0, 0, size, size)
            setTint(getColor(R.color.text))
            alpha = if (now) 255 else 0
        }
        setCompoundDrawablesRelative(check, null, null, null)
    }

    /** Re-reads every value, for answers that arrive late (the disk's sizes, a cleared cache). */
    private fun refresh() {
        rows.forEach { bind(it) }
        (currentFocus?.tag as? Option)?.let(::describe)
    }

    // ---- The sections. ----

    private fun playback(): List<Group> {
        val speeds = resources.getStringArray(R.array.player_speeds)
        return listOf(
            Group(R.string.settings_group_quality, listOf(
                cycle(R.string.settings_quality, text(R.string.settings_quality_help), Settings.QUALITIES, { Settings.quality }, { Settings.quality = it }) {
                    if (it == "Auto") getString(R.string.auto) else it
                },
                cycle(R.string.settings_audio, text(R.string.settings_audio_help), Settings.AUDIOS, { Settings.audio }, { Settings.audio = it }) {
                    when (it) {
                        "default" -> getString(R.string.auto)
                        "6ch" -> getString(R.string.settings_audio_51)
                        "2ch" -> getString(R.string.settings_audio_stereo)
                        else -> getString(R.string.audio_channel_n, it.removeSuffix("ch")).localDigits()
                    }
                },
                cycle(R.string.settings_speed, text(R.string.settings_speed_help), Settings.SPEEDS, { Settings.speed }, { Settings.speed = it }) {
                    speeds.getOrNull(Settings.SPEEDS.indexOf(it)) ?: "$it×"
                },
            )),
            Group(R.string.settings_group_watching, listOf(
                cycle(R.string.settings_buffer, text(R.string.settings_buffer_help), Settings.BUFFER_STEPS, { Settings.bufferPercent }, { Settings.bufferPercent = it },
                    note = ::bufferNote, ahead = { bufferMinutes().toFloat() / FILM_MINUTES }) {
                    if (it == 0) getString(R.string.settings_off) else getString(R.string.settings_percent, it)
                },
                toggle(R.string.settings_autoplay, text(R.string.settings_autoplay_help), { Settings.autoplay }, { Settings.autoplay = it }),
                toggle(R.string.settings_auto_skip, text(R.string.settings_auto_skip_help), { Settings.autoSkip }, { Settings.autoSkip = it }),
            )),
        )
    }

    /** Minutes of a two-hour 1080p film ready ahead of the picture, with the disk's cap where it binds; 0 when none. */
    private fun bufferMinutes(): Int {
        val percent = Settings.bufferPercent
        val room = readAhead
        if (percent == 0 || room == 0L) return 0
        val share = FILM_MINUTES * percent / 100
        val fits = room?.let { (it * 8 / BITRATE / 60).toInt() } ?: share
        return minOf(share, fits)
    }

    private fun bufferNote(): CharSequence {
        val percent = Settings.bufferPercent
        val minutes = bufferMinutes()
        return when {
            percent == 0 -> getString(R.string.settings_buffer_off)
            readAhead == 0L -> getString(R.string.settings_buffer_no_room)
            else -> getString(
                if (minutes < FILM_MINUTES * percent / 100) R.string.settings_buffer_capped else R.string.settings_buffer_ahead,
                getString(R.string.settings_percent, percent),
                resources.getQuantityString(R.plurals.settings_minutes, minutes, minutes),
            )
        }
    }

    private fun subtitles(): List<Group> {
        val sizes = resources.getStringArray(R.array.subtitle_sizes)
        val colors = resources.getStringArray(R.array.subtitle_colors)
        val backgrounds = resources.getStringArray(R.array.subtitle_backgrounds)
        return listOf(
            Group(0, listOf(
                cycle(R.string.settings_subtitle_language, text(R.string.settings_subtitle_language_help), listOf("off", "fa", "en"), { Settings.subtitle }, { Settings.subtitle = it }) {
                    if (it == "off") getString(R.string.subtitle_off) else Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifEmpty { it }
                },
            )),
            Group(R.string.settings_group_look, listOf(
                cycle(R.string.subtitle_size, text(R.string.settings_subtitle_size_help), sizes.indices.toList(), { Settings.subtitleSize }, { Settings.subtitleSize = it }) { sizes[it] },
                cycle(R.string.subtitle_color, text(R.string.settings_subtitle_color_help), colors.indices.toList(), { Settings.subtitleColor }, { Settings.subtitleColor = it }) { colors[it] },
                cycle(R.string.subtitle_background, text(R.string.settings_subtitle_background_help), backgrounds.indices.toList(), { Settings.subtitleBackground }, { Settings.subtitleBackground = it }) { backgrounds[it] },
            )),
        )
    }

    /**
     * A line of subtitle as the player sets it: the same share of the screen's height, colours and
     * box, its baseline 9% above the bottom edge, in the language chosen.
     */
    // ponytail: a shadow stands in for the player's black outline when there is no box; share CueView if the two ever drift.
    private fun styleSample() {
        val height = resources.displayMetrics.heightPixels
        val em = height * Settings.subtitleHeightShare
        val box = Settings.subtitleBoxColor
        val english = Settings.subtitle == "en"
        sample.setText(if (english) R.string.settings_sample_en else R.string.settings_sample_fa)
        sample.textDirection = if (english) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_RTL
        sample.setTextSize(TypedValue.COMPLEX_UNIT_PX, em)
        sample.setTextColor(Settings.subtitleTextColor)
        sample.background = if (Color.alpha(box) == 0) null else GradientDrawable().apply {
            setColor(box)
            cornerRadius = 0.12f * em
        }
        if (Color.alpha(box) == 0) sample.setShadowLayer(0.12f * em, 0f, 0.03f * em, Color.BLACK) else sample.setShadowLayer(0f, 0f, 0f, 0)
        val pad = (0.35f * em).toInt()
        sample.setPadding(pad, 0, pad, 0)
        (sample.layoutParams as FrameLayout.LayoutParams).bottomMargin = (height * 0.09f).toInt() - sample.paint.fontMetricsInt.descent
        sample.requestLayout()
    }

    private fun app(): List<Group> = listOf(
        Group(R.string.settings_group_interface, listOf(
            Option(
                R.string.language, text(R.string.settings_language_help), value = { getString(R.string.language_current) },
                values = listOf(getString(R.string.settings_language_fa), getString(R.string.settings_language_en)),
                current = { if (Api.language == "fa") 0 else 1 },
            ) { switchLanguage() },
            // The system's own animations off reduces motion whatever this says; the note then says so.
            toggle(R.string.settings_reduce_motion, text(R.string.settings_reduce_motion_help), { Settings.reduceMotion }, { Settings.reduceMotion = it }) {
                if (Motion.systemReduced) getString(R.string.settings_reduce_motion_system) else null
            },
        )),
        Group(R.string.settings_group_storage, listOf(
            Option(R.string.settings_image_cache, text(R.string.settings_image_cache_help), value = { size(imageBytes) }, trail = R.drawable.settings_ic_clear) { clearImages() },
            Option(R.string.settings_video_cache, ::videoHelp, value = { size(videoBytes) }, trail = R.drawable.settings_ic_clear) { clearVideo() },
        )),
        Group(R.string.settings_group_about, listOf(
            Option(R.string.version, text(R.string.settings_version_help), value = { BuildConfig.VERSION_NAME.localDigits() }),
            Option(R.string.app_update, text(R.string.app_update_help), value = ::updateValue,
                note = ::updateNote, trail = R.drawable.player_chevron) {
                when (val status = AppUpdates.state.value) {
                    is AppUpdates.Status.Ready -> AppUpdates.openInstall(this)
                    is AppUpdates.Status.Failed -> when (status.reason) {
                        AppUpdates.Failure.INCOMPATIBLE_SIGNATURE -> runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(AppUpdates.RELEASES_URL)))
                        }.onFailure {
                            android.widget.Toast.makeText(this, getString(R.string.update_browser_missing), android.widget.Toast.LENGTH_LONG).show()
                        }
                        AppUpdates.Failure.INSTALLER_UNAVAILABLE -> AppUpdates.openInstall(this)
                        else -> AppUpdates.check(force = true)
                    }
                    else -> AppUpdates.check(force = true)
                }
            },
        )),
    )

    private fun updateValue(): CharSequence = when (val status = AppUpdates.state.value) {
        AppUpdates.Status.Idle -> getString(R.string.update_check)
        AppUpdates.Status.Checking -> getString(R.string.update_checking)
        AppUpdates.Status.Current -> getString(R.string.update_current)
        is AppUpdates.Status.Ready -> getString(R.string.update_install)
        is AppUpdates.Status.Failed -> getString(if (status.reason == AppUpdates.Failure.INCOMPATIBLE_SIGNATURE) R.string.update_releases else R.string.update_retry)
        is AppUpdates.Status.Downloading -> if (status.total > 0) {
            getString(R.string.settings_percent, (status.bytes * 100 / status.total).coerceIn(0, 100).toInt())
        } else getString(R.string.update_downloading)
    }

    private fun updateNote(): CharSequence? = when (val status = AppUpdates.state.value) {
        is AppUpdates.Status.Ready -> getString(R.string.update_ready, status.version)
        is AppUpdates.Status.Downloading -> getString(R.string.update_download_version, status.version)
        is AppUpdates.Status.Failed -> getString(when (status.reason) {
            AppUpdates.Failure.INCOMPATIBLE_SIGNATURE -> R.string.update_signature_mismatch
            AppUpdates.Failure.INVALID_APK, AppUpdates.Failure.INVALID_RELEASE -> R.string.update_invalid
            AppUpdates.Failure.INSTALLER_UNAVAILABLE -> R.string.update_no_installer
            else -> R.string.update_failed
        })
        else -> null
    }

    private fun videoHelp(): CharSequence = when (val room = videoRoom) {
        0L -> getString(R.string.settings_buffer_no_room)
        else -> getString(R.string.settings_video_cache_help, room?.let { Formatter.formatShortFileSize(this, it) } ?: "…")
    }

    private fun size(bytes: Long?): CharSequence? = when (bytes) {
        null -> null
        0L -> getString(R.string.settings_empty)
        else -> Formatter.formatShortFileSize(this, bytes)
    }

    /** The app comes back up in the other language, home underneath and this page on top, on the same row. */
    private fun switchLanguage() {
        Api.language = if (Api.language == "fa") "en" else "fa"
        val here = Intent(this, SettingsActivity::class.java).putExtra(EXTRA_SECTION, APP).putExtra(EXTRA_ROW, 0)
        MainActivity.restart(this, guest = !Api.loggedIn, then = here)
    }

    private fun measure() {
        val app = applicationContext
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                imageBytes = SingletonImageLoader.get(app).diskCache?.size ?: 0L
                videoRoom = Buffering.capacity(app)
                readAhead = Buffering.readAhead(app)
                videoBytes = Buffering.cacheBytes(app)
            }
            refresh()
        }
    }

    private fun clearImages() {
        val app = applicationContext
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                SingletonImageLoader.get(app).run {
                    diskCache?.clear()
                    memoryCache?.clear()
                }
            }
            imageBytes = 0L
            refresh()
        }
    }

    private fun clearVideo() {
        val app = applicationContext
        lifecycleScope.launch {
            videoBytes = withContext(Dispatchers.IO) {
                Buffering.clearCache(app)
                Buffering.cacheBytes(app)
            }
            refresh()
        }
    }

    /**
     * A service the viewer brings their own key to: the page its key comes from, the key in use
     * now (theirs, else the build's), and one light call that tries a key.
     */
    private class Service(
        val id: String, @StringRes val name: Int, @StringRes val unlocks: Int, @StringRes val help: Int,
        val link: String, val key: () -> String, val check: suspend (String) -> Boolean,
    )

    private enum class Check { RUNNING, WORKS, REJECTED, UNREACHABLE }

    private val keyed = listOf(
        Service(Tmdb.NAME, R.string.service_tmdb, R.string.settings_tmdb_unlocks, R.string.settings_tmdb_help,
            "https://www.themoviedb.org/settings/api", { Tmdb.key }, Tmdb::check),
        Service(Subtitles.SUBDL, R.string.service_subdl, R.string.settings_subdl_unlocks, R.string.settings_subdl_help,
            "https://subdl.com/panel/api", { Subtitles.subdlKey }, Subtitles::checkSubdl),
        Service(Subtitles.OPENSUBTITLES, R.string.service_opensubtitles, R.string.settings_opensubtitles_unlocks,
            R.string.settings_opensubtitles_help, "https://www.opensubtitles.com/consumers", { Subtitles.openSubtitlesKey }, Subtitles::checkOpenSubtitles),
    )

    /** The models that write Persian subtitles in the player (Translator.kt), under their own title, the one it uses first on top. */
    private val translators = listOf(
        Service(Translator.CLAUDE.id, R.string.service_claude, R.string.settings_translator_unlocks, R.string.settings_claude_help,
            Translator.CLAUDE.link, { Translator.CLAUDE.key }, Translator::checkClaude),
        Service(Translator.OPENAI.id, R.string.service_openai, R.string.settings_translator_unlocks, R.string.settings_openai_help,
            Translator.OPENAI.link, { Translator.OPENAI.key }, Translator::checkOpenAi),
        Service(Translator.GEMINI.id, R.string.service_gemini, R.string.settings_translator_unlocks, R.string.settings_gemini_help,
            Translator.GEMINI.link, { Translator.GEMINI.key }, Translator::checkGemini),
    )

    /** What checking a key on saving found, by service, with the key it tried. ponytail: for this page's life; persist it if a rejected key should still say so next time. */
    private val checks = HashMap<String, Pair<String, Check>>()

    /** One row per service: OK opens the sheet that takes its key. */
    private fun services(): List<Group> =
        listOf(Group(0, keyed.map(::keyRow)), Group(R.string.settings_translators, listOf(modelRow()) + translators.map(::keyRow)))

    /**
     * Which model makes Persian subtitles, every provider's in one list, so picking a model picks its
     * provider. Before any pick it shows the one in use; the note says what a film costs on it, or,
     * for a model whose key isn't in yet, which model translates meanwhile.
     */
    private fun modelRow() = cycle(R.string.settings_translator_model, text(R.string.settings_translator_model_help), Translator.MODELS,
        { Translator.picked ?: Translator.model ?: Translator.MODELS.first() }, { Settings.translatorModel = it.id }, note = ::modelNote) { it.name }

    private fun modelNote(): CharSequence {
        val model = Translator.model ?: return getString(R.string.settings_model_no_key)
        Translator.picked?.takeIf { it !== model }?.let { return getString(R.string.settings_model_missing_key, it.provider.id, model.name) }
        // Dollars as the app writes numbers: «۰٫۹۲» in Persian, 0.92 in English.
        val dollars = String.format(Locale.US, "%.2f", model.cents / 100.0).let { if (Api.language == "fa") it.replace('.', '٫').persianDigits() else it }
        return getString(if (model.free) R.string.settings_model_cost_free else R.string.settings_model_cost, dollars)
    }

    private fun keyRow(s: Service) =
        Option(s.name, text(s.help), value = { keyValue(s) }, note = { keyNote(s) }, sub = s.unlocks, warn = { outcome(s) == Check.REJECTED }) {
            ask(getString(s.name), Settings.savedKey(s.id), s.link) { key, answer -> save(s, key, answer) }
        }

    private fun outcome(s: Service) = checks[s.id]?.takeIf { it.first == Settings.savedKey(s.id) }?.second

    /** Not set, being checked, refused, or the key's last four characters: enough to tell keys apart, never the key. */
    private fun keyValue(s: Service): CharSequence {
        val key = s.key()
        return when {
            key.isEmpty() -> getString(R.string.settings_key_none)
            outcome(s) == Check.RUNNING -> getString(R.string.settings_key_checking)
            outcome(s) == Check.REJECTED -> getString(R.string.settings_key_rejected)
            else -> getString(R.string.settings_key_last, key.takeLast(4))
        }
    }

    private fun keyNote(s: Service): CharSequence? = when {
        Settings.savedKey(s.id).isEmpty() -> if (s.key().isEmpty()) null else getString(R.string.settings_key_built)
        else -> when (outcome(s)) {
            Check.WORKS -> getString(R.string.settings_key_works)
            Check.REJECTED -> getString(R.string.settings_key_rejected_note)
            Check.UNREACHABLE -> getString(R.string.settings_key_unreachable, getString(s.name))
            else -> null
        }
    }

    /**
     * Saves the key at once and then tries it: saved whatever the answer, since from Iran the
     * service may be out of reach while the key is fine. Keys are printable ASCII; anything else
     * (a paste's line break, a Persian keyboard's letter) would not survive an HTTP header. The
     * row and its panel say what the try found, and so does the sheet on top of them ([answer]).
     */
    private fun save(s: Service, typed: String, answer: (CharSequence?, Boolean?) -> Unit) {
        val key = typed.filter { it in '!'..'~' }
        Settings.saveKey(s.id, key)
        if (key.isNotEmpty()) {
            checks[s.id] = key to Check.RUNNING
            lifecycleScope.launch {
                val works = try { s.check(key) } catch (e: IOException) { null }
                checks[s.id] = key to when (works) {
                    true -> Check.WORKS
                    false -> Check.REJECTED
                    null -> Check.UNREACHABLE
                }
                refresh()
                answer(keyNote(s), works)
            }
        } else answer(null, null)
        refresh()
    }

    /** Signed in, the panel's one row signs out (the card above it says as whom); a guest's opens sign-in. */
    private fun account(): List<Group> = listOf(Group(0, listOf(
        if (!Api.loggedIn) {
            Option(R.string.sign_in, text(R.string.settings_sign_in_help), trail = R.drawable.player_chevron) {
                startActivity(Intent(this, LoginActivity::class.java))
            }
        } else {
            Option(R.string.sign_out, text(R.string.settings_sign_out_help), value = { if (signingOut) getString(R.string.settings_signing_out) else null },
                icon = R.drawable.settings_ic_sign_out, danger = true) { askSignOut() }
        },
    )))

    /** Who this TV is signed in as: the name's initial, the name, the plan's badge and the days left on it, red in the last week. */
    private fun accountCard(): View {
        val card = layoutInflater.inflate(R.layout.settings_account, options, false)
        val avatar = card.findViewById<TextView>(R.id.settings_avatar)
        val name = card.findViewById<TextView>(R.id.settings_account_name)
        val tier = card.findViewById<TextView>(R.id.settings_tier)
        val days = card.findViewById<TextView>(R.id.settings_days)
        val user = Api.user.takeIf { Api.loggedIn }
        if (user == null) {
            avatar.foreground = getDrawable(R.drawable.settings_ic_account)
            name.text = getString(R.string.guest)
            tier.visibility = View.GONE
            days.text = getString(R.string.settings_guest_line)
            days.typeface = medium(days.text)
            return card
        }
        val userName = user.str("user_name") ?: getString(R.string.settings_account)
        val face = font(if (userName.isLatin) R.font.inter_bold else R.font.peyda_bold)
        avatar.text = userName.take(1).uppercase()
        avatar.typeface = face
        name.text = userName
        name.typeface = face
        val type = user.str("user_type")
        val plan = userType(type)
        if (plan == null) tier.visibility = View.GONE else {
            tier.text = getString(R.string.settings_tier, plan)
            tier.typeface = medium(tier.text)
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(tierColor(type))
                setSize((8 * dp).toInt(), (8 * dp).toInt())
            }
            // Persian comes down onto the badge's middle, as in a chip; the dot, inset by as much
            // below, stays on it.
            val nudge = nudge(tier.text)
            tier.setPaddingRelative(tier.paddingStart, nudge, tier.paddingEnd, 0)
            tier.setCompoundDrawablesRelativeWithIntrinsicBounds(InsetDrawable(dot, 0, 0, 0, nudge), null, null, null)
        }
        // user_expire: {days, g: Gregorian date, j: Jalali date}; no days left reads as nothing.
        val left = user.obj("user_expire")?.optInt("days")?.takeIf { it > 0 }
        days.text = left?.let { getString(R.string.remaining_days, it) }
        days.typeface = medium(days.text)
        if (left != null && left < 7) days.setTextColor(getColor(R.color.settings_danger))
        return card
    }

    private fun userType(type: String?) = when (type) {
        "golden" -> getString(R.string.user_type_golden)
        "red" -> getString(R.string.user_type_red)
        "blue" -> getString(R.string.user_type_blue)
        "free" -> getString(R.string.user_type_free)
        "staff" -> getString(R.string.user_type_staff)
        "admin" -> getString(R.string.user_type_admin)
        "robot" -> getString(R.string.user_type_robot)
        else -> type
    }

    /** 30nama names its plans by colour; the badge's dot is that colour. */
    private fun tierColor(type: String?) = getColor(
        when (type) {
            "golden" -> R.color.settings_tier_golden
            "red" -> R.color.red
            "blue" -> R.color.settings_tier_blue
            "free" -> R.color.green
            else -> R.color.text_dim
        },
    )

    /** Asks first, on the app's sheet: the name, the question, and Cancel under the remote so a stray OK changes nothing. */
    private fun askSignOut() {
        if (signingOut) return
        val name = Api.user?.str("user_name") ?: getString(R.string.settings_account)
        confirm(name, getString(R.string.sign_out_confirm), getString(R.string.sign_out)) { signOut() }
    }

    private fun signOut() {
        signingOut = true
        refresh()
        lifecycleScope.launch {
            // The server hears of it if it answers in time; a hanging VPN does not keep the TV signed in.
            withTimeoutOrNull(5_000) { runCatching { Api.logout() } }
            forgetSession(this@SettingsActivity)
            startActivity(Intent(this@SettingsActivity, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    // ---- Helpers. ----

    private fun text(@StringRes id: Int): () -> CharSequence = { getString(id) }

    /** Text the app sets itself, in its script's medium face: "1080p" and "24 MB" in Inter whole, % and all. */
    private fun medium(text: CharSequence) = font(if (text.isLatin) R.font.inter_medium else R.font.vazirmatn_medium)

    /** Top padding that sets [text] on the middle of a fixed-height pill: Persian's face sits high there. */
    private fun nudge(text: CharSequence) = if (text.isLatin) 0 else resources.getDimensionPixelSize(R.dimen.settings_chip_nudge)

    private fun onOff(on: Boolean) = getString(if (on) R.string.settings_on else R.string.settings_off)

    /** A setting OK steps through: [base], plus the value set now if it is none of them (one the player saved). */
    private fun <T> cycle(
        @StringRes label: Int, help: () -> CharSequence, base: List<T>, get: () -> T, set: (T) -> Unit,
        note: () -> CharSequence? = { null }, ahead: (() -> Float)? = null, name: (T) -> CharSequence,
    ): Option {
        val values = (base + get()).distinct()
        return Option(label, help, value = { name(get()) }, values = values.map(name), current = { values.indexOf(get()) }, note = note, ahead = ahead) {
            set(values[(values.indexOf(get()) + 1) % values.size])
        }
    }

    /** A setting that is on or off: a switch, which OK flips. */
    private fun toggle(@StringRes label: Int, help: () -> CharSequence, get: () -> Boolean, set: (Boolean) -> Unit, note: () -> CharSequence? = { null }) =
        Option(label, help, values = listOf(onOff(true), onOff(false)), current = { if (get()) 0 else 1 }, on = get, note = note) { set(!get()) }

    /** Up from the first item and Down from the last go nowhere, rather than out of the list. */
    private fun stopAtEnds(list: List<View>) {
        list.firstOrNull()?.let { it.nextFocusUpId = it.id }
        list.lastOrNull()?.let { it.nextFocusDownId = it.id }
    }

    companion object {
        private const val EXTRA_SECTION = "section"
        private const val EXTRA_ROW = "row"
        private const val SUBTITLES = 1
        private const val APP = 2
        private const val ACCOUNT = 4
        private const val SEAM = "seam"
        /**
         * A row's lift: 2.7% of a 296dp row is 4dp a side, so its white card stops 8dp inside the
         * panel's edge (12dp padding), where its 12dp corner runs parallel to the panel's 20dp.
         */
        private const val ROW_LIFT = 1.027f
        /** The film the read-ahead is told in: two hours of 1080p at about 5 Mbps, the low end of 30nama's 1080p. */
        private const val FILM_MINUTES = 120
        private const val BITRATE = 5_000_000L
    }
}
