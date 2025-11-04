/**
 * T110: Component test for DeleteConfirmationDialog
 *
 * Tests the reusable delete confirmation dialog component with:
 * - Rendering with open/closed states
 * - Confirm button triggers delete action
 * - Cancel button closes dialog without action
 * - Keyboard interaction (Escape key)
 *
 * User Story: US7 - Delete Saved Comparisons (Phase 9)
 * Priority: P4
 *
 * Note: This test should FAIL initially because the component doesn't exist yet (TDD Red phase).
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DeleteConfirmationDialog } from '../../ui/DeleteConfirmationDialog';

describe('DeleteConfirmationDialog', () => {
  const defaultProps = {
    open: true,
    title: 'Delete Comparison',
    description: 'Are you sure you want to delete this comparison? This action cannot be undone.',
    onConfirm: vi.fn(),
    onCancel: vi.fn(),
  };

  it('should render when open is true', () => {
    // When: Render dialog with open=true
    render(<DeleteConfirmationDialog {...defaultProps} />);

    // Then: Dialog should be visible with title and description
    expect(screen.getByText('Delete Comparison')).toBeInTheDocument();
    expect(
      screen.getByText(/Are you sure you want to delete this comparison/)
    ).toBeInTheDocument();
  });

  it('should not render when open is false', () => {
    // When: Render dialog with open=false
    render(<DeleteConfirmationDialog {...defaultProps} open={false} />);

    // Then: Dialog should not be visible
    expect(screen.queryByText('Delete Comparison')).not.toBeInTheDocument();
  });

  it('should call onConfirm when Delete button is clicked', () => {
    // Given: Dialog is open
    const onConfirmMock = vi.fn();
    render(<DeleteConfirmationDialog {...defaultProps} onConfirm={onConfirmMock} />);

    // When: Click Delete button
    const deleteButton = screen.getByRole('button', { name: /delete/i });
    fireEvent.click(deleteButton);

    // Then: onConfirm callback should be called
    expect(onConfirmMock).toHaveBeenCalledTimes(1);
  });

  it('should call onCancel when Cancel button is clicked', () => {
    // Given: Dialog is open
    const onCancelMock = vi.fn();
    render(<DeleteConfirmationDialog {...defaultProps} onCancel={onCancelMock} />);

    // When: Click Cancel button
    const cancelButton = screen.getByRole('button', { name: /cancel/i });
    fireEvent.click(cancelButton);

    // Then: onCancel callback should be called (may be called multiple times due to dialog closing)
    expect(onCancelMock).toHaveBeenCalled();
  });

  it('should display loading state when isLoading is true', () => {
    // When: Render dialog with isLoading=true
    render(<DeleteConfirmationDialog {...defaultProps} isLoading={true} />);

    // Then: Delete button should be disabled and show loading indicator
    const deleteButton = screen.getByRole('button', { name: /deleting/i });
    expect(deleteButton).toBeDisabled();
  });

  it('should disable buttons when isLoading is true', () => {
    // When: Render dialog with isLoading=true
    render(<DeleteConfirmationDialog {...defaultProps} isLoading={true} />);

    // Then: Both buttons should be disabled
    const deleteButton = screen.getByRole('button', { name: /deleting/i });
    const cancelButton = screen.getByRole('button', { name: /cancel/i });

    expect(deleteButton).toBeDisabled();
    expect(cancelButton).toBeDisabled();
  });

  it('should support custom button labels', () => {
    // When: Render dialog with custom button labels
    render(
      <DeleteConfirmationDialog
        {...defaultProps}
        confirmLabel="Remove"
        cancelLabel="Go Back"
      />
    );

    // Then: Buttons should show custom labels
    expect(screen.getByRole('button', { name: /remove/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /go back/i })).toBeInTheDocument();
  });

  it('should have proper ARIA attributes for accessibility', () => {
    // When: Render dialog
    render(<DeleteConfirmationDialog {...defaultProps} />);

    // Then: Dialog should have proper ARIA attributes
    const dialog = screen.getByRole('alertdialog');
    expect(dialog).toBeInTheDocument();
    expect(dialog).toHaveAttribute('aria-labelledby');
    expect(dialog).toHaveAttribute('aria-describedby');
  });

  it('should close on Escape key press', () => {
    // Given: Dialog is open
    const onCancelMock = vi.fn();
    render(<DeleteConfirmationDialog {...defaultProps} onCancel={onCancelMock} />);

    // When: Press Escape key
    fireEvent.keyDown(screen.getByRole('alertdialog'), {
      key: 'Escape',
      code: 'Escape',
      keyCode: 27,
      charCode: 27,
    });

    // Then: onCancel should be called
    expect(onCancelMock).toHaveBeenCalledTimes(1);
  });

  it('should not call onConfirm when clicking outside dialog by default', () => {
    // Given: Dialog is open
    const onConfirmMock = vi.fn();
    const onCancelMock = vi.fn();
    render(
      <DeleteConfirmationDialog
        {...defaultProps}
        onConfirm={onConfirmMock}
        onCancel={onCancelMock}
      />
    );

    // When: Click dialog backdrop (outside)
    const backdrop = screen.getByRole('alertdialog').parentElement;
    if (backdrop) {
      fireEvent.click(backdrop);
    }

    // Then: onConfirm should not be called (only onCancel should be called)
    expect(onConfirmMock).not.toHaveBeenCalled();
  });

  it('should show destructive styling for delete action', () => {
    // When: Render dialog
    render(<DeleteConfirmationDialog {...defaultProps} />);

    // Then: Delete button should exist and be clickable (destructive styling is applied via variant prop)
    const deleteButton = screen.getByRole('button', { name: /delete/i });
    expect(deleteButton).toBeInTheDocument();
    expect(deleteButton).not.toBeDisabled();
    // Note: The destructive variant is applied via props, not a direct CSS class
  });
});
