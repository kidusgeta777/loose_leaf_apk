import { Platform } from 'react-native';
import { requireNativeModule } from 'expo-modules-core';

// The floating circle is an Android overlay, so everything here is Android-only.
const Native = Platform.OS === 'android' ? requireNativeModule('LooseOverlay') : null;

export default {
  supported: !!Native,
  canDrawOverlays: () => (Native ? Native.canDrawOverlays() : false),
  requestOverlayPermission: () => Native && Native.requestOverlayPermission(),
  /** Starts the floating circle. Returns false if permission or a saved key is missing. */
  start: () => (Native ? Native.start() : false),
  stop: () => Native && Native.stop(),
  /** The user's "on" choice (survives app restarts). */
  isEnabled: () => (Native ? Native.isEnabled() : false),
  isRunning: () => (Native ? Native.isRunning() : false),
  /** Only ONE config (server + key) is ever stored; saving replaces it. */
  saveConfig: (endpoint, key) => Native && Native.saveConfig(endpoint, key),
  getConfig: () => (Native ? Native.getConfig() : { endpoint: '', key: '' }),
  clearConfig: () => Native && Native.clearConfig(),
};
