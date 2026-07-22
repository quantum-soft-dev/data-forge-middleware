import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/shared/ui/ui/alert-dialog';

describe('AlertDialog (monitoring treatment, T010)', () => {
  it('action inherits the brand button, cancel the hairline outline', () => {
    render(
      <AlertDialog open>
        <AlertDialogContent data-testid="content">
          <AlertDialogHeader>
            <AlertDialogTitle>Rebuild checkpoint now?</AlertDialogTitle>
            <AlertDialogDescription>Outside the regular schedule.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction>Rebuild now</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>,
    );
    const action = screen.getByRole('button', { name: 'Rebuild now' });
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    expect(action.className).toContain('bg-brand');
    expect(action.className).toContain('hover:bg-brand-hover');
    expect(cancel.className).toContain('border-hairline');
    // content radius rides on --radius (10px after T002)
    expect(screen.getByTestId('content').className).toContain('sm:rounded-lg');
  });
});
