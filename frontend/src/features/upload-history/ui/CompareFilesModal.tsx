/**
 * Modal dialog for selecting target batch to compare files
 *
 * Shows compact dialog with searchable combobox for batch selection.
 * Allows user to select target batch from searchable dropdown.
 *
 * Feature: 009-markdown-user-story (User Story 1/2 integration)
 */

import { useState } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { useBatchHistory } from '@/entities/batch/api/queries';
import { useCreateComparison } from '@/features/file-comparison/lib/useCreateComparison';
import { toast } from 'sonner';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/shared/ui/ui/dialog';
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/shared/ui/ui/command';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/shared/ui/ui/popover';
import { Button } from '@/shared/ui/ui/button';
import { Badge } from '@/shared/ui/ui/badge';
import { Loader2, ChevronsUpDown, Check, Calendar, Files, HardDrive } from 'lucide-react';
import { formatDateTime } from '@/shared/lib/formatters';

interface CompareFilesModalProps {
  /** Is modal open */
  isOpen: boolean;
  /** Close modal callback */
  onClose: () => void;
  /** Current batch ID (source) */
  currentBatchId: string;
  /** Selected file IDs to compare */
  selectedFileIds: string[];
}

/**
 * Modal dialog for target batch selection and comparison creation
 */
export function CompareFilesModal({
  isOpen,
  onClose,
  currentBatchId,
  selectedFileIds,
}: CompareFilesModalProps) {
  const [selectedTargetBatchId, setSelectedTargetBatchId] = useState<string>('');
  const [comboboxOpen, setComboboxOpen] = useState(false);
  const navigate = useNavigate();

  // Fetch available batches for comparison
  const { data: batchesData, isLoading: isBatchesLoading } = useBatchHistory(50);

  // Create comparison mutation
  const createComparisonMutation = useCreateComparison({
    onSuccess: (data) => {
      toast.success('Comparison created successfully!', {
        description: `Redirecting to comparison results...`,
      });
      onClose();
      setSelectedTargetBatchId('');
      setComboboxOpen(false);

      // Navigate to comparison detail page (Updated 2025-11-03)
      navigate({ to: `/account/comparisons/${data.id}` });
    },
    onError: (error) => {
      toast.error('Failed to create comparison', {
        description: error.message,
      });
    },
  });

  const handleCreate = () => {
    if (!selectedTargetBatchId) return;

    createComparisonMutation.mutate({
      currentBatchId,
      targetBatchId: selectedTargetBatchId,
      fileIds: selectedFileIds.length > 0 ? selectedFileIds : null,
    });
  };

  // Filter out current batch from available batches
  const allBatches = batchesData?.pages.flatMap(page => page.items) ?? [];
  const availableBatches = allBatches.filter((batch) => batch.id !== currentBatchId);

  // Find selected batch for display
  const selectedBatch = availableBatches.find((batch) => batch.id === selectedTargetBatchId);

  return (
    <Dialog open={isOpen} onOpenChange={onClose}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Select Batch to Compare</DialogTitle>
          <DialogDescription>
            Choose a target batch to compare {selectedFileIds.length > 0 ? `${selectedFileIds.length} selected` : 'your'} files against.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-4">
          {isBatchesLoading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
              <span className="ml-2 text-sm text-muted-foreground">Loading batches...</span>
            </div>
          ) : availableBatches.length === 0 ? (
            <div className="py-8 text-center">
              <p className="text-sm text-muted-foreground">No other batches available for comparison.</p>
            </div>
          ) : (
            <>
              <div className="space-y-2">
                <label className="text-sm font-medium">Target Batch</label>
                <Popover open={comboboxOpen} onOpenChange={setComboboxOpen}>
                  <PopoverTrigger asChild>
                    <Button
                      variant="outline"
                      role="combobox"
                      aria-expanded={comboboxOpen}
                      className="w-full justify-between"
                    >
                      {selectedBatch ? (
                        <span className="truncate">{formatDateTime(selectedBatch.startedAt)}</span>
                      ) : (
                        <span className="text-muted-foreground">Select batch...</span>
                      )}
                      <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
                    </Button>
                  </PopoverTrigger>
                  <PopoverContent className="w-[--radix-popover-trigger-width] p-0">
                    <Command>
                      <CommandInput placeholder="Search batches..." />
                      <CommandList>
                        <CommandEmpty>No batches found.</CommandEmpty>
                        <CommandGroup>
                          {availableBatches.map((batch) => (
                            <CommandItem
                              key={batch.id}
                              value={batch.id}
                              onSelect={(value) => {
                                setSelectedTargetBatchId(value === selectedTargetBatchId ? '' : value);
                                setComboboxOpen(false);
                              }}
                            >
                              <Check
                                className={`mr-2 h-4 w-4 ${
                                  selectedTargetBatchId === batch.id ? 'opacity-100' : 'opacity-0'
                                }`}
                              />
                              <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-2">
                                  <Calendar className="h-3 w-3 text-muted-foreground flex-shrink-0" />
                                  <span className="text-sm truncate">{formatDateTime(batch.startedAt)}</span>
                                </div>
                                <div className="flex items-center gap-3 mt-1 text-xs text-muted-foreground">
                                  <span className="flex items-center gap-1">
                                    <Files className="h-3 w-3" />
                                    {batch.fileCount}
                                  </span>
                                  <span className="flex items-center gap-1">
                                    <HardDrive className="h-3 w-3" />
                                    {Math.round(batch.totalSize / 1024)} KB
                                  </span>
                                  <Badge
                                    variant={
                                      batch.status === 'COMPLETED'
                                        ? 'success'
                                        : batch.status === 'IN_PROGRESS'
                                        ? 'default'
                                        : 'secondary'
                                    }
                                    className="text-xs"
                                  >
                                    {batch.status}
                                  </Badge>
                                </div>
                              </div>
                            </CommandItem>
                          ))}
                        </CommandGroup>
                      </CommandList>
                    </Command>
                  </PopoverContent>
                </Popover>
              </div>

              {/* Selected batch preview */}
              {selectedBatch && (
                <div className="rounded-lg border bg-muted/50 p-3 space-y-2">
                  <div className="text-xs font-medium text-muted-foreground">Selected Batch Details</div>
                  <div className="grid grid-cols-3 gap-2 text-sm">
                    <div>
                      <div className="text-xs text-muted-foreground">Files</div>
                      <div className="font-medium">{selectedBatch.fileCount}</div>
                    </div>
                    <div>
                      <div className="text-xs text-muted-foreground">Size</div>
                      <div className="font-medium">{Math.round(selectedBatch.totalSize / 1024)} KB</div>
                    </div>
                    <div>
                      <div className="text-xs text-muted-foreground">Status</div>
                      <Badge
                        variant={
                          selectedBatch.status === 'COMPLETED'
                            ? 'success'
                            : selectedBatch.status === 'IN_PROGRESS'
                            ? 'default'
                            : 'secondary'
                        }
                        className="text-xs"
                      >
                        {selectedBatch.status}
                      </Badge>
                    </div>
                  </div>
                </div>
              )}
            </>
          )}
        </div>

        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => {
              onClose();
              setComboboxOpen(false);
            }}
            disabled={createComparisonMutation.isPending}
          >
            Cancel
          </Button>
          <Button
            onClick={handleCreate}
            disabled={!selectedTargetBatchId || createComparisonMutation.isPending}
          >
            {createComparisonMutation.isPending ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                Creating...
              </>
            ) : (
              'Create Comparison'
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
