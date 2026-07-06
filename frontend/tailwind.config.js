/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ['class'],
  content: [
    './pages/**/*.{ts,tsx}',
    './components/**/*.{ts,tsx}',
    './app/**/*.{ts,tsx}',
    './src/**/*.{ts,tsx}',
  ],
  prefix: '',
  theme: {
    container: {
      center: true,
      padding: '2rem',
      screens: {
        '2xl': '1400px',
      },
    },
    extend: {
      fontFamily: {
        // Geist globally (023, F13; product decision P1 = yes)
        sans: ['"Geist Sans"', 'Geist', 'ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'Helvetica Neue', 'Arial', 'sans-serif'],
      },
      colors: {
        // Monitoring semantic utilities (024, T003) — values mirror shared/ui/tokens.ts
        ink: {
          DEFAULT: '#2B2827',
          secondary: '#736F6D',
          muted: '#A3A3A3',
          title: '#403C3B',
        },
        brand: {
          DEFAULT: '#3C82D8',
          hover: '#3676C4',
          50: '#EBF2FB',
          100: '#E0ECFA',
        },
        hairline: 'rgba(0,0,0,0.12)',
        separator: 'rgba(0,0,0,0.06)',
        surface: {
          subtle: '#F5F5F4',
          hover: '#FAFAFA',
          active: '#F8F8F8',
          shell: '#EFEFEF',
        },
        danger: {
          text: '#B91C1C',
          solid: '#EF4444',
          'solid-hover': '#DC2626',
          border: 'rgba(239,68,68,0.35)',
          bg: '#FEF2F2',
        },
        border: 'hsl(var(--border))',
        input: 'hsl(var(--input))',
        ring: 'hsl(var(--ring))',
        background: 'hsl(var(--background))',
        foreground: 'hsl(var(--foreground))',
        primary: {
          DEFAULT: 'hsl(var(--primary))',
          foreground: 'hsl(var(--primary-foreground))',
        },
        secondary: {
          DEFAULT: 'hsl(var(--secondary))',
          foreground: 'hsl(var(--secondary-foreground))',
        },
        destructive: {
          DEFAULT: 'hsl(var(--destructive))',
          foreground: 'hsl(var(--destructive-foreground))',
        },
        muted: {
          DEFAULT: 'hsl(var(--muted))',
          foreground: 'hsl(var(--muted-foreground))',
        },
        accent: {
          DEFAULT: 'hsl(var(--accent))',
          foreground: 'hsl(var(--accent-foreground))',
        },
        popover: {
          DEFAULT: 'hsl(var(--popover))',
          foreground: 'hsl(var(--popover-foreground))',
        },
        card: {
          DEFAULT: 'hsl(var(--card))',
          foreground: 'hsl(var(--card-foreground))',
        },
      },
      borderRadius: {
        lg: 'var(--radius)',
        md: 'calc(var(--radius) - 2px)',
        sm: 'calc(var(--radius) - 4px)',
      },
      boxShadow: {
        // Monitoring layered shadows (024, T003) — values mirror shared/ui/tokens.ts
        card: '0 20px 87.5px rgba(0,0,0,0.02), 0 0 1.75px rgba(0,0,0,0.16)',
        'card-inner': '0 1px 1.75px rgba(0,0,0,0.25), 0 0 0.5px rgba(0,0,0,0.04)',
        'icon-circle': '0 5px 4.375px rgba(0,0,0,0.01), 0 5px 6.125px rgba(0,0,0,0.05)',
      },
      keyframes: {
        'accordion-down': {
          from: { height: '0' },
          to: { height: 'var(--radix-accordion-content-height)' },
        },
        'accordion-up': {
          from: { height: 'var(--radix-accordion-content-height)' },
          to: { height: '0' },
        },
      },
      animation: {
        'accordion-down': 'accordion-down 0.2s ease-out',
        'accordion-up': 'accordion-up 0.2s ease-out',
      },
    },
  },
  plugins: [require('tailwindcss-animate')],
}
