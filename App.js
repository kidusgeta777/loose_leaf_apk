import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert, AppState, KeyboardAvoidingView, PermissionsAndroid, Platform, Pressable, ScrollView,
  StatusBar, StyleSheet, Switch, Text, TextInput, View,
} from 'react-native';
import LooseOverlay from './modules/loose-overlay';

const COLORS = {
  bg: '#14123a', bg2: '#231a54', card: 'rgba(255,255,255,0.10)', line: 'rgba(255,255,255,0.22)',
  text: '#ffffff', soft: 'rgba(255,255,255,0.68)', green: '#4cd964', ink: '#2a1f5e', red: '#ff8a8a',
};

/** "mysite.com/loose" -> "https://mysite.com/loose/api.php" */
function normalizeEndpoint(raw) {
  let u = (raw || '').trim();
  if (!u) return '';
  if (!/^https?:\/\//i.test(u)) u = 'https://' + u;
  u = u.split('#')[0].split('?')[0].replace(/\/+$/, '');
  if (!/\/api\.php$/i.test(u)) u += '/api.php';
  return /^https?:\/\/[^\s/]+\S*$/i.test(u) ? u : '';
}

/** Checks the key against the Loose Leaf server (idea_list on that key's own file). */
async function verifyKey(endpoint, key) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 12000);
  try {
    const res = await fetch(endpoint + '?action=idea_list&limit=1', {
      headers: { Authorization: 'Bearer ' + key, 'X-API-Key': key, Accept: 'application/json' },
      signal: ctrl.signal,
    });
    const body = await res.text();
    let json = null;
    try { json = JSON.parse(body); } catch (e) { /* not JSON */ }
    if (!json) throw new Error('That address did not answer like Loose Leaf. Check the server address.');
    if (res.status === 401) throw new Error('Invalid API key.');
    if (!json.ok) throw new Error(json.error || 'Server error (' + res.status + ').');
    return json.count || 0;
  } catch (e) {
    if (e && e.name === 'AbortError') throw new Error('The server did not answer in time.');
    if (e instanceof TypeError) throw new Error('Cannot reach the server. Check the address and your connection.');
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

function hostOf(endpoint) {
  return (endpoint || '').replace(/^https?:\/\//i, '').replace(/\/api\.php$/i, '');
}

export default function App() {
  const [saved, setSaved] = useState(null);           // { endpoint, key } or null (only ONE key at a time)
  const [on, setOn] = useState(false);
  const [endpointIn, setEndpointIn] = useState('');
  const [keyIn, setKeyIn] = useState('');
  const [showKey, setShowKey] = useState(false);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [msg, setMsg] = useState({ kind: 'info', text: '' });
  const wantOn = useRef(false);   // user asked for "on" and we are waiting for the overlay permission

  const readConfig = () => {
    const c = LooseOverlay.getConfig();
    return c && c.key && c.endpoint ? { endpoint: c.endpoint, key: c.key } : null;
  };

  const askNotifications = async () => {
    if (Platform.OS === 'android' && Platform.Version >= 33) {
      try { await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS); } catch (e) { /* optional */ }
    }
  };

  const turnOn = useCallback(async () => {
    if (!readConfig()) {
      Alert.alert('Save a key first', 'Enter your server address and API key, then save them before turning the circle on.');
      return;
    }
    if (!LooseOverlay.canDrawOverlays()) {
      Alert.alert(
        'Allow display over other apps',
        'The floating circle needs the "Display over other apps" permission. Turn it on for Loose, then come back.',
        [
          { text: 'Cancel', style: 'cancel' },
          { text: 'Open settings', onPress: () => { wantOn.current = true; LooseOverlay.requestOverlayPermission(); } },
        ]
      );
      return;
    }
    await askNotifications();
    setOn(!!LooseOverlay.start());
  }, []);

  const turnOff = useCallback(() => {
    wantOn.current = false;
    LooseOverlay.stop();
    setOn(false);
  }, []);

  // Sync with the native side whenever the app comes back to the front.
  const refresh = useCallback(() => {
    setSaved(readConfig());
    if (wantOn.current && LooseOverlay.canDrawOverlays()) {
      wantOn.current = false;
      turnOn();
      return;
    }
    const enabled = LooseOverlay.isEnabled();
    if (enabled && LooseOverlay.canDrawOverlays() && !LooseOverlay.isRunning()) LooseOverlay.start();
    setOn(enabled && LooseOverlay.canDrawOverlays());
  }, [turnOn]);

  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', (s) => { if (s === 'active') refresh(); });
    return () => sub.remove();
  }, [refresh]);

  const onSave = async () => {
    const endpoint = normalizeEndpoint(endpointIn || (saved && saved.endpoint) || '');
    const key = keyIn.trim();
    if (!endpoint) { setMsg({ kind: 'err', text: 'Enter your Loose Leaf server address.' }); return; }
    if (!key) { setMsg({ kind: 'err', text: 'Enter an API key first.' }); return; }
    setBusy(true);
    setMsg({ kind: 'info', text: 'Checking the key…' });
    try {
      const count = await verifyKey(endpoint, key);
      LooseOverlay.saveConfig(endpoint, key);   // replaces any key saved before
      setSaved({ endpoint, key });
      setEndpointIn('');
      setKeyIn('');
      setShowKey(false);
      setMsg({ kind: 'ok', text: 'Key saved. This file has ' + count + ' idea' + (count === 1 ? '' : 's') + '.' });
      setDone(true);
      setTimeout(() => setDone(false), 1600);
    } catch (e) {
      setMsg({ kind: 'err', text: e.message || 'Could not save the key.' });
    } finally {
      setBusy(false);
    }
  };

  const onRemove = () => {
    Alert.alert('Remove the saved key?', 'The floating circle will turn off.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Remove', style: 'destructive',
        onPress: () => {
          wantOn.current = false;
          LooseOverlay.clearConfig();
          setSaved(null);
          setOn(false);
          setMsg({ kind: 'info', text: 'Key removed.' });
        },
      },
    ]);
  };

  if (!LooseOverlay.supported) {
    return (
      <View style={[s.root, s.center]}>
        <Text style={s.title}>Loose</Text>
        <Text style={[s.soft, { textAlign: 'center', marginTop: 8 }]}>The floating circle only works on Android.</Text>
      </View>
    );
  }

  const msgColor = msg.kind === 'err' ? COLORS.red : msg.kind === 'ok' ? COLORS.green : COLORS.soft;

  return (
    <KeyboardAvoidingView style={s.root} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <StatusBar barStyle="light-content" backgroundColor={COLORS.bg} />
      <ScrollView
        contentContainerStyle={s.scroll}
        keyboardShouldPersistTaps="handled"
        showsVerticalScrollIndicator={false}
      >
        <Text style={s.title}>Loose</Text>

        <View style={[s.card, s.row]}>
          <View style={{ flex: 1, paddingRight: 12 }}>
            <Text style={s.cardTitle}>Floating circle</Text>
            <Text style={s.small}>{on ? 'On: the circle is showing' : 'Off: the circle is hidden'}</Text>
          </View>
          <Switch
            value={on}
            onValueChange={(v) => (v ? turnOn() : turnOff())}
            trackColor={{ false: 'rgba(255,255,255,0.25)', true: COLORS.green }}
            thumbColor="#ffffff"
          />
        </View>

        <View style={s.middle}>
          <View style={[s.card, { width: '100%' }]}>
            <Text style={s.cardTitle}>API key</Text>
            <Text style={[s.small, { marginBottom: 14 }]}>
              Paste your Loose Leaf key and save it. Only one key is kept at a time; saving a new one replaces it.
            </Text>

            {saved ? (
              <View style={s.savedBox}>
                <View style={{ flex: 1 }}>
                  <Text style={s.savedTitle}>Saved key  ••••{saved.key.slice(-4)}</Text>
                  <Text style={s.small} numberOfLines={1}>{hostOf(saved.endpoint)}</Text>
                </View>
                <Pressable onPress={onRemove} hitSlop={10}>
                  <Text style={{ color: COLORS.red, fontWeight: '700' }}>Remove</Text>
                </Pressable>
              </View>
            ) : null}

            <TextInput
              style={s.input}
              value={endpointIn}
              onChangeText={setEndpointIn}
              placeholder={saved ? 'Server address (' + hostOf(saved.endpoint) + ')' : 'Server address, e.g. mysite.com/loose'}
              placeholderTextColor="rgba(255,255,255,0.55)"
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
            />
            <View style={s.keyRow}>
              <TextInput
                style={[s.input, { flex: 1, marginBottom: 0 }]}
                value={keyIn}
                onChangeText={setKeyIn}
                placeholder="Paste API key"
                placeholderTextColor="rgba(255,255,255,0.55)"
                secureTextEntry={!showKey}
                autoCapitalize="none"
                autoCorrect={false}
                onSubmitEditing={onSave}
              />
              <Pressable onPress={() => setShowKey((v) => !v)} style={s.eye} hitSlop={8}>
                <Text style={s.eyeText}>{showKey ? 'Hide' : 'Show'}</Text>
              </Pressable>
            </View>

            <Pressable
              onPress={onSave}
              disabled={busy}
              style={({ pressed }) => [s.save, done && s.saveDone, pressed && { opacity: 0.85 }, busy && { opacity: 0.6 }]}
            >
              <Text style={[s.saveText, done && { color: '#0b3d1a' }]}>{busy ? 'Checking…' : done ? 'Saved ✓' : 'Save'}</Text>
            </Pressable>
            <Text style={[s.msg, { color: msgColor }]}>{msg.text}</Text>
          </View>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: COLORS.bg },
  center: { alignItems: 'center', justifyContent: 'center', padding: 24 },
  scroll: { flexGrow: 1, paddingHorizontal: 18, paddingTop: (StatusBar.currentHeight || 24) + 20, paddingBottom: 34 },
  title: { color: COLORS.text, fontSize: 26, fontWeight: '700', letterSpacing: 0.3, marginBottom: 18 },
  card: { backgroundColor: COLORS.card, borderColor: COLORS.line, borderWidth: 1, borderRadius: 22, paddingVertical: 16, paddingHorizontal: 18 },
  row: { flexDirection: 'row', alignItems: 'center' },
  cardTitle: { color: COLORS.text, fontSize: 16, fontWeight: '700', marginBottom: 3 },
  small: { color: COLORS.soft, fontSize: 13, lineHeight: 18 },
  soft: { color: COLORS.soft, fontSize: 15 },
  middle: { flex: 1, justifyContent: 'center', paddingVertical: 24 },
  savedBox: {
    flexDirection: 'row', alignItems: 'center', padding: 12, marginBottom: 12, borderRadius: 14,
    backgroundColor: 'rgba(76,217,100,0.14)', borderColor: 'rgba(76,217,100,0.45)', borderWidth: 1,
  },
  savedTitle: { color: COLORS.text, fontSize: 14, fontWeight: '700', marginBottom: 2 },
  input: {
    height: 48, borderRadius: 14, paddingHorizontal: 16, marginBottom: 12, color: COLORS.text, fontSize: 16,
    backgroundColor: 'rgba(255,255,255,0.14)', borderColor: 'rgba(255,255,255,0.3)', borderWidth: 1,
  },
  keyRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 12 },
  eye: { paddingHorizontal: 12, height: 48, justifyContent: 'center' },
  eyeText: { color: COLORS.soft, fontWeight: '600' },
  save: { height: 48, borderRadius: 14, backgroundColor: '#ffffff', alignItems: 'center', justifyContent: 'center' },
  saveDone: { backgroundColor: COLORS.green },
  saveText: { color: COLORS.ink, fontSize: 16, fontWeight: '700' },
  msg: { minHeight: 18, marginTop: 10, textAlign: 'center', fontSize: 13 },
});
