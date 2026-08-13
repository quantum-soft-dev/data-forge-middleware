/**
 * One-shot plugin secret.
 *
 * The backend returns the freshly minted secret exactly once — in the activation
 * response (`apiKey`) or in a rotation response — and keeps only a hash. The raw
 * value therefore lives in component state for as long as the reveal dialog is
 * open and is never cached, logged or put into a toast.
 *
 * The field carries different shapes per plugin: bit-bi sends the raw API key,
 * parquet-export sends `login:password` Basic Auth credentials.
 */

/** Plugin whose secret is Basic Auth credentials rather than a single key. */
export const PARQUET_EXPORT_PLUGIN_ID = 'parquet-export'

export type PluginSecret =
  | { kind: 'api-key'; pluginId: string; value: string }
  | { kind: 'basic-auth'; pluginId: string; login: string; password: string }

/**
 * Turn the raw secret of an activation or rotation response into a displayable shape.
 *
 * @param pluginId - plugin the secret belongs to
 * @param raw - raw secret from the response, absent when nothing was issued
 * @returns the parsed secret, or null when the response carried none
 */
export function parsePluginSecret(
  pluginId: string,
  raw: string | null | undefined
): PluginSecret | null {
  if (!raw || raw.trim() === '') {
    return null
  }

  if (pluginId === PARQUET_EXPORT_PLUGIN_ID) {
    const separator = raw.indexOf(':')
    const login = raw.slice(0, separator)
    const password = raw.slice(separator + 1)

    // An unexpected shape is still shown verbatim rather than dropped: the owner
    // has no second chance to read it.
    if (separator > 0 && password !== '') {
      return { kind: 'basic-auth', pluginId, login, password }
    }
  }

  return { kind: 'api-key', pluginId, value: raw }
}
