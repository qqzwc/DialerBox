package com.waynell.dialerbox;

import com.android.providers.contacts.HanziToPinyin;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Vibrator;
import android.text.TextUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * @author Wayne
 */
public class XposedMod implements IXposedHookZygoteInit, IXposedHookLoadPackage {

	private static Context mContext;

	private static XSharedPreferences mPrefsPhone;

	private static Vibrator mVibrator;

	private static Object mPreviousCallState;

	public static final String PACKAGE_NAME = XposedMod.class.getPackage().getName();

	public static final List<String> PACKAGE_NAMES = new ArrayList<>(Arrays.asList(
			"com.google.android.dialer", "com.android.dialer"));

	// Dialer App
	private static final String CLASS_PHONE_DIALER = "com.android.dialer.DialerApplication";

	// CallLog
	private static final String CLASS_PHONE_CALL_DETAILS = "com.android.dialer.PhoneCallDetails";

	private static final String CLASS_PHONE_CALL_DETAILS_HELPER = "com.android.dialer.PhoneCallDetailsHelper";

	private static final String CLASS_PHONE_CALL_DETAILS_HELPER_27 = "com.android.dialer.app.calllog.PhoneCallDetailsHelper";	
	
	private static final String CLASS_PHONE_CALL_DETAILS_HELPER_23 = "com.android.dialer.calllog.PhoneCallDetailsHelper";

	// InCall
	private static final String CLASS_DIALTACTS_ACTIVITY = "com.android.dialer.DialtactsActivity";

	private static final String CLASS_DIALTACTS_ACTIVITY_GOOGLE =
		"com.google.android.dialer.extensions.GoogleDialtactsActivity";

	private static final String CLASS_CALL_CARD_FRAGMENT = "com.android.incallui.CallCardFragment";

	private static final String CLASS_CALL_CALL = "com.android.incallui.Call";

	private static final String CLASS_CALL_CONTACT_CACHE_ENTRY = "com.android.incallui.ContactInfoCache.ContactCacheEntry";

	private static final String CLASS_CALL_STATUS_BAR_NOTIFIER = "com.android.incallui.StatusBarNotifier";

	// android.telecom.Call.Details.CAPABILITY_GENERIC_CONFERENCE
	private static final int CAPABILITY_GENERIC_CONFERENCE = 0x00004000;

	// Vibrate
	private static final String CLASS_IN_CALL_PRESENTER = "com.android.incallui.InCallPresenter";

	private static final String ENUM_IN_CALL_STATE = "com.android.incallui.InCallPresenter$InCallState";

	private static final String CLASS_CALL_LIST = "com.android.incallui.CallList";

	private static final int CALL_STATE_ACTIVE = Build.VERSION.SDK_INT >= 22 ? 3 : 2;

	private static final int CALL_STATE_INCOMING = Build.VERSION.SDK_INT >= 22 ? 4 : 3;

	private static final int CALL_STATE_WAITING = Build.VERSION.SDK_INT >= 22 ? 5 : 4;

	@Override
	public void initZygote(StartupParam startupParam) throws Throwable {
		mPrefsPhone = new XSharedPreferences(PACKAGE_NAME);
		mPrefsPhone.makeWorldReadable();
	}

	@Override
	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
		if (PACKAGE_NAMES.contains(loadPackageParam.packageName)) {
			getDialerContext(loadPackageParam);
			addVibrate(loadPackageParam);
			addCallLogGeocode(loadPackageParam);
			addCallGeocode(loadPackageParam);
			addSmartDial(loadPackageParam);
			showDialPad(loadPackageParam);
		}
	}

	private void getDialerContext(XC_LoadPackage.LoadPackageParam param) {
		XposedHelpers.findAndHookMethod(CLASS_PHONE_DIALER, param.classLoader, "onCreate", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				long time = computeTime(0);
				mContext = (Context) XposedHelpers.callMethod(param.thisObject, "getApplicationContext");
				log("DialerApplication.getDialerContext: " + mContext + "-" + computeTime(time));
			}
		});
	}

	private void addCallLogGeocode(XC_LoadPackage.LoadPackageParam param) {
		try {
            String helperClassFullName = "";
			final ClassLoader loader = param.classLoader;
			final Class<?> classPhoneCallDetails = XposedHelpers.findClass(CLASS_PHONE_CALL_DETAILS, loader);

            if (Build.VERSION.SDK_INT >= 27) {
                helperClassFullName = CLASS_PHONE_CALL_DETAILS_HELPER_27;
            } else if (Build.VERSION.SDK_INT >= 23) {
                helperClassFullName = CLASS_PHONE_CALL_DETAILS_HELPER_23;
            } else if (Build.VERSION.SDK_INT >= 21) {
                helperClassFullName = CLASS_PHONE_CALL_DETAILS_HELPER;
            }

            final Class<?> classPhoneCallDetailsHelper = XposedHelpers.findClass(helperClassFullName, loader);

			if (classPhoneCallDetails == null || classPhoneCallDetailsHelper == null) {
				return;
			}

			//com.android.dialer.PhoneCallDetailsHelper#getCallTypeOrLocation(PhoneCallDetails details)
			XposedHelpers.findAndHookMethod(classPhoneCallDetailsHelper, "getCallTypeOrLocation", classPhoneCallDetails, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					mPrefsPhone.reload();
					if(!mPrefsPhone.getBoolean(SettingsActivity.KEY_CALL_LOG_ATTR, false)) {
						return;
					}

                    String fieldName = "";
                    if (Build.VERSION.SDK_INT >= 23) {
                        fieldName = "nameAlternative";
                    } else if (Build.VERSION.SDK_INT >= 21) {
                        fieldName = "name";
                    }

					String name = (String) XposedHelpers.getObjectField(param.args[0], fieldName);
					String number = (String) XposedHelpers.getObjectField(param.args[0], "number");

					if (!TextUtils.isEmpty(number)) {
						String geocode = GeocoderUtils.getGeocodedLocationFor(mContext,
								number, loader, mPrefsPhone.getBoolean(SettingsActivity.KEY_CALL_FOR_CHINA, false));
						if (!TextUtils.isEmpty(geocode)) {
							if (TextUtils.isEmpty(name)) {
								param.setResult(geocode);
							} else {
								param.setResult(param.getResult() + " " + geocode);
							}
						}
					}
				}
			});
		} catch (Throwable throwable) {
			log(throwable);
		}
	}

	private void addCallGeocode(XC_LoadPackage.LoadPackageParam param) {
		try {
			final ClassLoader loader = param.classLoader;
			final Class<?> classCallCardFragment = XposedHelpers.findClass(CLASS_CALL_CARD_FRAGMENT, loader);

			if (classCallCardFragment == null) {
				return;
			}

            XC_MethodHook addCallHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    mPrefsPhone.reload();
                    if (!mPrefsPhone.getBoolean(SettingsActivity.KEY_CALL_INCOMING_ATTR, true)) {
                        return;
                    }

                    long time = computeTime(0);
                    // String number, String name, boolean nameIsNumber, String label
                    // Contact people --- xxxxx, xx, false, mobile etc.
                    // Not contact people --- null, xxxxx, true, null.

                    String label = param.args[3] == null ? "" : (String) param.args[3];
                    if (!(boolean) param.args[2] && !TextUtils.isEmpty((String) param.args[0])) {
                        param.args[3] = label + " " + GeocoderUtils.getGeocodedLocationFor(mContext,
                                (String) param.args[0], loader, mPrefsPhone.getBoolean(SettingsActivity.KEY_CALL_FOR_CHINA, false));
                    } else if ((boolean) param.args[2] && !TextUtils.isEmpty((String) param.args[1])) {
                        param.args[3] = GeocoderUtils.getGeocodedLocationFor(mContext,
                                (String) param.args[1], loader, mPrefsPhone.getBoolean(SettingsActivity.KEY_CALL_FOR_CHINA, false));
                        if (param.args[0] != null) {
                            param.args[0] = null;
                        }
                    }
                    log("CallCardFragment.setPrimary: " + param.args[3] + "-" + computeTime(time));
                }
            };

            if (Build.VERSION.SDK_INT >= 23) {
                XposedHelpers.findAndHookMethod(classCallCardFragment, "setPrimary", String.class, String.class, boolean.class,
                        String.class, Drawable.class, boolean.class, boolean.class, addCallHook);
            }else if (Build.VERSION.SDK_INT >= 21) {
                // com.android.incallui.CardFragment#setPrimary(String number, String name,
                // boolean nameIsNumber, String label, Drawable photo, boolean isSipCall)
                XposedHelpers.findAndHookMethod(classCallCardFragment, "setPrimary", String.class, String.class, boolean.class,
                        String.class, Drawable.class, boolean.class, addCallHook);
            }


			//com.android.incallui.StatusBarNotifier#getContentTitle(ContactCacheEntry contactInfo, Call call)
			Class<?> classContactCacheEntry = XposedHelpers.findClass(CLASS_CALL_CONTACT_CACHE_ENTRY, param.classLoader);
			Class<?> classCall = XposedHelpers.findClass(CLASS_CALL_CALL, param.classLoader);

			if (classContactCacheEntry == null || classCall == null) {
				return;
			}

			XposedHelpers.findAndHookMethod(CLASS_CALL_STATUS_BAR_NOTIFIER, param.classLoader, "getContentTitle", classContactCacheEntry, classCall,
				new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) throws Throwable {
						mPrefsPhone.reload();
						if (!mPrefsPhone.getBoolean(SettingsActivity.KEY_CALL_INCOMING_ATTR, true)) {
							return;
						}

						long time = computeTime(0);
						boolean isConferenceCall = (boolean) XposedHelpers.callMethod(param.args[1], "isConferenceCall");
						boolean isCan = (boolean) XposedHelpers
							.callMethod(param.args[1], "can", new Class[]{int.class}, CAPABILITY_GENERIC_CONFERENCE);
						if (!isConferenceCall || isCan) {
							String number = (String) XposedHelpers.getObjectField(param.args[0], "number");
							if (!TextUtils.isEmpty(number)) {
								String geocode = GeocoderUtils.getGeocodedLocationFor(mContext,
										number, loader, mPrefsPhone.getBoolean(SettingsActivity.KEY_CALL_FOR_CHINA, false));
								if (!TextUtils.isEmpty(geocode)) {
									param.setResult(param.getResult() + " " + geocode);
								}
							}
						}
						log("StatusBarNotifier.getContentTitle: " + param.getResult().toString() + "-" + computeTime(time));
					}
				});
		} catch (Throwable throwable) {
			log(throwable);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void addVibrate(XC_LoadPackage.LoadPackageParam param) {
		try {
			final ClassLoader classLoader = param.classLoader;

			Class<?> mClassInCallPresenter = XposedHelpers.findClass(CLASS_IN_CALL_PRESENTER, classLoader);

			final Class<? extends Enum> enumInCallState = (Class<? extends Enum>)
				XposedHelpers.findClass(ENUM_IN_CALL_STATE, classLoader);

			XposedBridge.hookAllMethods(mClassInCallPresenter, "setUp", new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					mContext = (Context) param.args[0];
					/*if (mSensorManager == null) {
						mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
						if (BuildConfig.DEBUG) log("InCallPresenter.setUp(); mSensorManager created");
					}*/
					if (mVibrator == null) {
						mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
						log("InCallPresenter.setUp(); mVibrator created");
					}
					/*if (mHandler == null) {
						mHandler = new Handler();
					}
					if (mWakeLock == null) {
						PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
						mWakeLock  = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
					}*/
					mPreviousCallState = null;
				}
			});

			XposedBridge.hookAllMethods(mClassInCallPresenter, "onIncomingCall", new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					mPrefsPhone.reload();
					Integer state = (Integer) XposedHelpers.callMethod(param.args[0], "getState");
					log("onIncomingCall: state = " + state);
					/*if (state == CALL_STATE_INCOMING) {
						mIncomingCall = param.args[0];
						attachSensorListener();
					}*/
					if (state == CALL_STATE_WAITING ||
						(state == CALL_STATE_INCOMING && mPreviousCallState == Enum.valueOf(enumInCallState, "INCALL")
							&& mPrefsPhone.getBoolean(SettingsActivity.KEY_CALL_VIBRATION_WAITING, true))) {
						vibrate(100, 0, 0);
					}
				}
			});

			XposedBridge.hookAllMethods(mClassInCallPresenter, "onDisconnect", new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					mPrefsPhone.reload();
					if (mPrefsPhone.getBoolean(SettingsActivity.KEY_CALL_VIBRATION_DISCONNECTED, true)) {
						log("Call disconnected; executing vibrate on call disconnected");
						vibrate(100, 0, 0);
					}
				}
			});

			XposedHelpers.findAndHookMethod(mClassInCallPresenter, "getPotentialStateFromCallList",
				CLASS_CALL_LIST, new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) throws Throwable {
						mPrefsPhone.reload();
						Object state = param.getResult();
						log("InCallPresenter.getPotentialStateFromCallList(); InCallState = " + state);
						/*if (mPreviousCallState == null ||
							mPreviousCallState == Enum.valueOf(enumInCallState, "NO_CALLS")) {
							refreshPhonePrefs();
						}*/

						/*if (state != Enum.valueOf(enumInCallState, "INCOMING")) {
							mIncomingCall = null;
							detachSensorListener();
						}*/

						if (state == Enum.valueOf(enumInCallState, "INCALL")) {
							Object activeCall = XposedHelpers.callMethod(param.args[0], "getActiveCall");
							if (activeCall != null) {
								final int callState = (Integer) XposedHelpers.callMethod(activeCall, "getState");
								log("Call state is: " + callState);
								final boolean activeOutgoing = (callState == CALL_STATE_ACTIVE &&
									mPreviousCallState == Enum.valueOf(enumInCallState, "OUTGOING"));
								if (activeOutgoing) {
									if (mPrefsPhone.getBoolean(SettingsActivity.KEY_CALL_VIBRATION_CONNECTED, true)) {
										log("Outgoing call connected; executing vibrate on call connected");
										vibrate(100, 0, 0);
									}
									/*if (mCallVibrations.contains(GravityBoxSettings.CV_PERIODIC) &&
										mHandler != null) {
										log("Outgoing call connected; starting periodic vibrations");
										mHandler.postDelayed(mPeriodicVibrator, 45000);
										if (mWakeLock != null) {
											mWakeLock.acquire(46000);
											log("Partial Wake Lock acquired");
										}
									}*/
								}
							}
						}/* else if (state == Enum.valueOf(enumInCallState, "NO_CALLS")) {
							if (mHandler != null) {
								mHandler.removeCallbacks(mPeriodicVibrator);
							}
							if (mWakeLock != null && mWakeLock.isHeld()) {
								mWakeLock.release();
								log("Partial Wake Lock released");
							}
						}*/

						mPreviousCallState = state;
					}
				});
		} catch(Throwable t) {
			log(t);
		}
	}

	private void addSmartDial(XC_LoadPackage.LoadPackageParam param) {
		try {
			final ClassLoader classLoader = param.classLoader;
			// public static ArrayList<String> com.android.dialer.dialpad.SmartDialPrefix.generateNamePrefixes(String index)
			XposedHelpers.findAndHookMethod("com.android.dialer.smartdial.SmartDialPrefix", classLoader,
				"generateNamePrefixes", String.class, new XC_MethodHook() {
					// Convert index into Pin Yin.
					@Override
					protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
						mPrefsPhone.reload();
						if (!mPrefsPhone.getBoolean(SettingsActivity.KEY_SMART_DIAL, false)) {
							return;
						}

						final String name = (String) param.args[0];
						if (name == null) {
							return;
						}
						final List<HanziToPinyin.Token> tokens = HanziToPinyin.getInstance().get(name);
						if (tokens.isEmpty()) {
							return;
						}
						final StringBuilder buffer = new StringBuilder();
						for (final HanziToPinyin.Token token : tokens) {
							buffer.append(' ').append(token.target);
						}
						final String full = buffer.substring(1).toLowerCase(Locale.US);
						log(name + ":" + full);
						param.args[0] = full;
					}
				});

			// boolean com.android.dialer.dialpad.SmartDialNameMatcher
			//	.matchesCombination(String displayName, String query, ArrayList<SmartDialMatchPosition> matchList)
			XposedHelpers.findAndHookMethod("com.android.dialer.smartdial.SmartDialNameMatcher", classLoader,
				"matchesCombination", String.class, String.class, ArrayList.class, new XC_MethodHook() {

					@Override
					protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
						mPrefsPhone.reload();
						if (!mPrefsPhone.getBoolean(SettingsActivity.KEY_SMART_DIAL, false)) {
							return;
						}

						final String name = (String) param.args[0];
						if (name == null) {
							return;
						}
						final List<HanziToPinyin.Token> tokens = HanziToPinyin.getInstance().get(name);
						if (tokens.isEmpty()) {
							return;
						}
						final StringBuilder full = new StringBuilder();
						for (final HanziToPinyin.Token token : tokens) {
							full.append(' ').append(token.target);
						}
						param.args[0] = full.substring(1).toLowerCase(Locale.US);
					}

					@Override
					protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
						mPrefsPhone.reload();
						if (!mPrefsPhone.getBoolean(SettingsActivity.KEY_SMART_DIAL, false)) {
							return;
						}

						final ArrayList<?> pos_list = (ArrayList<?>) param.args[2];
						pos_list.clear();
						log(param.args[1] + " ? " + param.args[0] + " = " + param.getResult());
					}
				});
		} catch (Throwable t) {
			log(t);
		}
	}

	private void showDialPad(XC_LoadPackage.LoadPackageParam param) {
		try {
			final Class<?> classDialtactsActivity = XposedHelpers.findClass(CLASS_DIALTACTS_ACTIVITY, param.classLoader);

			XposedHelpers.findAndHookMethod(classDialtactsActivity, "displayFragment", Intent.class, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					mPrefsPhone.reload();
					if (!mPrefsPhone.getBoolean(SettingsActivity.KEY_DIAL_PAD_SHOW, false)) {
						return;
					}

					Object dpFrag = XposedHelpers.getObjectField(param.thisObject, "mDialpadFragment");
					if (dpFrag != null) {
						final String realClassName = param.thisObject.getClass().getName();
						if (realClassName.equals(CLASS_DIALTACTS_ACTIVITY)) {
							XposedHelpers.callMethod(param.thisObject, "showDialpadFragment", false);
							log("showDialpadFragment() called within " + realClassName);
						} else if (realClassName.equals(CLASS_DIALTACTS_ACTIVITY_GOOGLE)) {
							final Class<?> superc = param.thisObject.getClass().getSuperclass();
							Method m = XposedHelpers.findMethodExact(superc, "showDialpadFragment", boolean.class);
							m.invoke(param.thisObject, false);
							log("showDialpadFragment() called within " + realClassName);
						}
					}
				}
			});
		} catch (Throwable t) {
			XposedBridge.log(t);
		}
	}

	private static void vibrate(int v1, int p1, int v2) {
		if (mVibrator == null) return;

		long[] pattern = new long[] { 0, v1, p1, v2 };
		mVibrator.vibrate(pattern, -1);
	}

	private static void log(Throwable throwable) {
		if (BuildConfig.DEBUG) {
			XposedBridge.log(throwable);
		}
	}

	private static void log(String log) {
		if (BuildConfig.DEBUG) {
			XposedBridge.log(log);
		}
	}

	private static long computeTime(long time) {
		return System.currentTimeMillis() - time;
	}
}
