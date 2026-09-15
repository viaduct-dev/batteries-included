import { createClient, SupabaseClient } from '@supabase/supabase-js';
// Database access goes through Viaduct; expose only Supabase Auth to the frontend.
type AuthClient = Pick<SupabaseClient, 'auth'>;

// Try to get config from environment variables first (local development)
const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL;
const SUPABASE_PUBLISHABLE_KEY = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY;

// Check if we have local config
const hasLocalConfig = Boolean(SUPABASE_URL && SUPABASE_PUBLISHABLE_KEY);

/**
 * Create Supabase client with the given config.
 */
function createSupabaseClient(url: string, key: string): AuthClient {
  const client = createClient(url, key, {
    auth: {
      storage: localStorage,
      persistSession: true,
      autoRefreshToken: true,
    }
  });
  return { auth: client.auth };
}

// Singleton instance - initialize immediately if we have local config
let supabaseInstance: AuthClient | null = hasLocalConfig
  ? createSupabaseClient(SUPABASE_URL!, SUPABASE_PUBLISHABLE_KEY!)
  : null;
let initPromise: Promise<AuthClient> | null = null;

/**
 * Initialize Supabase client.
 * - Uses env vars if available (local development)
 * - Otherwise fetches config from backend (production)
 */
export async function initSupabase(): Promise<AuthClient> {
  // Return existing instance if already initialized
  if (supabaseInstance) {
    return supabaseInstance;
  }

  // Return existing promise if initialization is in progress
  if (initPromise) {
    return initPromise;
  }

  initPromise = (async () => {
    // Use local config if available (already initialized above)
    if (hasLocalConfig && supabaseInstance) {
      console.log('[Supabase] Using local environment configuration');
      return supabaseInstance;
    }

    // Fetch config from backend
    console.log('[Supabase] Fetching configuration from backend...');
    try {
      // Import dynamically to avoid circular dependency
      const { fetchSupabaseConfig } = await import('@/lib/graphql');
      const config = await fetchSupabaseConfig();

      console.log('[Supabase] Configuration received, initializing client');
      supabaseInstance = createSupabaseClient(config.url, config.anonKey);
      return supabaseInstance;
    } catch (error) {
      console.error('[Supabase] Failed to fetch configuration:', error);
      throw new Error(
        'Failed to initialize Supabase. Make sure the backend is running and configured.'
      );
    }
  })();

  return initPromise;
}

/**
 * Get the Supabase client synchronously.
 * Throws if not initialized - call initSupabase() first.
 */
export function getSupabase(): AuthClient {
  if (!supabaseInstance) {
    throw new Error('Supabase not initialized. Call initSupabase() first.');
  }
  return supabaseInstance;
}

/**
 * Legacy export for backward compatibility.
 * Uses the singleton instance if available, otherwise creates a placeholder.
 */
export const supabase = supabaseInstance
  ?? createSupabaseClient('https://placeholder.supabase.co', 'placeholder-key');

// Export configuration status
export const supabaseConfigured = hasLocalConfig;
