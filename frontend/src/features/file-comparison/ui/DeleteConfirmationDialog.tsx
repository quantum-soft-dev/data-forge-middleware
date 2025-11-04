/**
 * T115: DeleteConfirmationDialog component
 *
 * Reusable confirmation dialog for delete actions with:
 * - Accessible AlertDialog from Radix UI
 * - Loading state support
 * - Keyboard interaction (Escape to cancel)
 * - Destructive action styling
 *
 * User Story: US7 - Delete Saved Comparisons (Phase 9)
 * Priority: P4
 *
 * @module features/file-comparison/ui/DeleteConfirmationDialog
 */

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
import { Button } from '@/shared/ui/ui/button';

export interface DeleteConfirmationDialogProps {
  /**
   * Whether the dialog is open
   */
  open: boolean;

  /**
   * Dialog title
   */
  title: string;

  /**
   * Dialog description/warning message
   */
  description: string;

  /**
   * Callback when user confirms deletion
   */
  onConfirm: () => void;

  /**
   * Callback when user cancels
   */
  onCancel: () => void;

  /**
   * Whether deletion is in progress (disables buttons)
   */
  isLoading?: boolean;

  /**
   * Custom label for confirm button (default: "Delete")
   */
  confirmLabel?: string;

  /**
   * Custom label for cancel button (default: "Cancel")
   */
  cancelLabel?: string;
}

/**
 * Reusable delete confirmation dialog.
 *
 * Renders an accessible AlertDialog with destructive styling for delete actions.
 * Supports loading state and keyboard interaction.
 *
 * @example
 * ```tsx
 * function MyComponent() {
 *   const [open, setOpen] = useState(false);
 *   const { mutate, isPending } = useDeleteComparison();
 *
 *   const handleConfirm = () => {
 *     mutate(comparisonId, {
 *       onSuccess: () => setOpen(false),
 *     });
 *   };
 *
 *   return (
 *     <>
 *       <button onClick={() => setOpen(true)}>Delete</button>
 *       <DeleteConfirmationDialog
 *         open={open}
 *         title="Delete Comparison"
 *         description="Are you sure? This action cannot be undone."
 *         onConfirm={handleConfirm}
 *         onCancel={() => setOpen(false)}
 *         isLoading={isPending}
 *       />
 *     </>
 *   );
 * }
 * ```
 */
export function DeleteConfirmationDialog({
  open,
  title,
  description,
  onConfirm,
  onCancel,
  isLoading = false,
  confirmLabel = 'Delete',
  cancelLabel = 'Cancel',
}: DeleteConfirmationDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel asChild>
            <Button variant="outline" disabled={isLoading} onClick={onCancel}>
              {cancelLabel}
            </Button>
          </AlertDialogCancel>
          <AlertDialogAction asChild>
            <Button variant="destructive" disabled={isLoading} onClick={onConfirm}>
              {isLoading ? 'Deleting...' : confirmLabel}
            </Button>
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
