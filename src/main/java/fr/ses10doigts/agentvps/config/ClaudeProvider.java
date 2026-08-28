package fr.ses10doigts.agentvps.config;

/**
 * Fournisseur utilise pour l'appel au CLI Claude Code (voir ClaudeCliProperties.provider).
 * ANTHROPIC appelle directement le binaire claude (API Anthropic native).
 * OPENROUTER route l'appel via Ori Harness (binaire "ori", voir openRouterBinaryPath) pour
 * utiliser un modele OpenRouter (voir openRouterModel) a la place de l'API Anthropic - voir
 * la memoire projet "ori_openrouter_integration" pour le contexte complet (exploration du
 * 28-29/08/2026).
 */
public enum ClaudeProvider {
    ANTHROPIC,
    OPENROUTER
}
