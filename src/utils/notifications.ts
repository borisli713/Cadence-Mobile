export type PushSupportState = 'granted' | 'denied' | 'default' | 'unsupported';

let swRegistration: ServiceWorkerRegistration | null = null;

export function getBrowserPushState(): PushSupportState {
  if (typeof window === 'undefined' || !('Notification' in window)) {
    return 'unsupported';
  }
  return Notification.permission as PushSupportState;
}

export async function registerNotificationServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (typeof window === 'undefined' || !('serviceWorker' in navigator)) {
    return null;
  }
  try {
    swRegistration = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
    return swRegistration;
  } catch (err) {
    console.warn('Service worker registration unavailable:', err);
    return null;
  }
}

export async function requestBrowserPushPermission(): Promise<PushSupportState> {
  if (typeof window === 'undefined' || !('Notification' in window)) {
    return 'unsupported';
  }
  try {
    await registerNotificationServiceWorker();
    const result = await Notification.requestPermission();
    return result as PushSupportState;
  } catch {
    return getBrowserPushState();
  }
}

export async function dispatchBrowserPushNotification(
  title: string,
  body: string,
  tag = 'cadence-shift-update'
): Promise<boolean> {
  if (typeof window === 'undefined' || !('Notification' in window)) {
    return false;
  }
  if (Notification.permission !== 'granted') {
    return false;
  }

  try {
    if (!swRegistration && 'serviceWorker' in navigator) {
      swRegistration = await navigator.serviceWorker.getRegistration('/') || null;
    }
    if (swRegistration && 'showNotification' in swRegistration) {
      await swRegistration.showNotification(title, {
        body,
        tag,
      });
      return true;
    }
    new Notification(title, { body, tag });
    return true;
  } catch {
    return false;
  }
}
