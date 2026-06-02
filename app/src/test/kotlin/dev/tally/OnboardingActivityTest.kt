package dev.tally

import android.content.Intent
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ViewFlipper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [OnboardingActivity].
 *
 * Acceptance criteria exercised:
 *   - Wizard starts at the correct step based on detected IME state.
 *   - Auto-advances forward when the user returns with Tally enabled/selected.
 *   - Never regresses to an earlier step on resume.
 *   - EXTRA_START_STEP allows launching directly at the TRY_IT step.
 *   - Done button finishes the activity.
 *   - Try-it field is pre-seeded with "2+2=" when the TRY_IT step is shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnboardingActivityTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun setTallySelected(selected: Boolean) {
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            if (selected) "${ctx.packageName}/.FakeIme" else "com.other/.OtherIme",
        )
    }

    // ── Initial step detection ────────────────────────────────────────────────

    @Test
    fun onCreate_nothingConfigured_showsEnableStep() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flipper = activity.findViewById<ViewFlipper>(R.id.onboarding_flipper)
                assertEquals(OnboardingStep.ENABLE.ordinal, flipper.displayedChild)
            }
        }
    }

    @Test
    fun onCreate_tallyAlreadySelected_showsTryItStep() {
        setTallySelected(true)
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flipper = activity.findViewById<ViewFlipper>(R.id.onboarding_flipper)
                assertEquals(OnboardingStep.TRY_IT.ordinal, flipper.displayedChild)
            }
        }
    }

    @Test
    fun extraStartStep_tryIt_showsTryItStep() {
        val intent = Intent(ctx, OnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_START_STEP, OnboardingStep.TRY_IT.name)
        ActivityScenario.launch<OnboardingActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val flipper = activity.findViewById<ViewFlipper>(R.id.onboarding_flipper)
                assertEquals(OnboardingStep.TRY_IT.ordinal, flipper.displayedChild)
            }
        }
    }

    // ── Auto-advance on resume ────────────────────────────────────────────────

    @Test
    fun onResume_tallySelectedAfterLaunch_advancesToTryIt() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            // Initially on ENABLE step.
            scenario.onActivity { activity ->
                val flipper = activity.findViewById<ViewFlipper>(R.id.onboarding_flipper)
                assertEquals(OnboardingStep.ENABLE.ordinal, flipper.displayedChild)
            }

            // Simulate user going to settings, selecting Tally, and returning.
            scenario.moveToState(Lifecycle.State.CREATED)
            setTallySelected(true)
            scenario.moveToState(Lifecycle.State.RESUMED)

            scenario.onActivity { activity ->
                val flipper = activity.findViewById<ViewFlipper>(R.id.onboarding_flipper)
                assertEquals(OnboardingStep.TRY_IT.ordinal, flipper.displayedChild)
            }
        }
    }

    @Test
    fun onResume_alreadyAtTryIt_doesNotRegress() {
        setTallySelected(true)
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            // Verify we are at TRY_IT.
            scenario.onActivity { activity ->
                assertEquals(
                    OnboardingStep.TRY_IT.ordinal,
                    activity.findViewById<ViewFlipper>(R.id.onboarding_flipper).displayedChild,
                )
            }

            // Simulate user switching to a different keyboard and returning.
            scenario.moveToState(Lifecycle.State.CREATED)
            setTallySelected(false)
            scenario.moveToState(Lifecycle.State.RESUMED)

            // Wizard must not go backward — stays at TRY_IT.
            scenario.onActivity { activity ->
                assertEquals(
                    OnboardingStep.TRY_IT.ordinal,
                    activity.findViewById<ViewFlipper>(R.id.onboarding_flipper).displayedChild,
                )
            }
        }
    }

    // ── Done button ───────────────────────────────────────────────────────────

    @Test
    fun doneButton_finishesActivity() {
        val intent = Intent(ctx, OnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_START_STEP, OnboardingStep.TRY_IT.name)
        ActivityScenario.launch<OnboardingActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.btn_done).performClick()
                // isFinishing is set synchronously when finish() is called.
                assertTrue(activity.isFinishing)
            }
        }
    }

    // ── Try-it seeding ────────────────────────────────────────────────────────

    @Test
    fun tryItStep_fieldPreSeeded_contains2plus2equals() {
        val intent = Intent(ctx, OnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_START_STEP, OnboardingStep.TRY_IT.name)
        ActivityScenario.launch<OnboardingActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val field = activity.findViewById<EditText>(R.id.try_it_field)
                assertEquals("2+2=", field.text.toString())
            }
        }
    }

    @Test
    fun tryItStep_existingText_notOverwritten() {
        val intent = Intent(ctx, OnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_START_STEP, OnboardingStep.TRY_IT.name)
        ActivityScenario.launch<OnboardingActivity>(intent).use { scenario ->
            // Replace the pre-seeded text.
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.try_it_field).setText("10*5=")
            }

            // Pause and resume — seed logic must not overwrite user-entered text.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            scenario.onActivity { activity ->
                assertEquals("10*5=", activity.findViewById<EditText>(R.id.try_it_field).text.toString())
            }
        }
    }

    // ── Step 1 button ─────────────────────────────────────────────────────────

    @Test
    fun step1Button_opensInputMethodSettings() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.btn_open_ime_settings).performClick()
                val nextIntent = shadowOf(activity).nextStartedActivity
                assertNotNull(nextIntent)
                assertEquals(Settings.ACTION_INPUT_METHOD_SETTINGS, nextIntent?.action)
            }
        }
    }
}
