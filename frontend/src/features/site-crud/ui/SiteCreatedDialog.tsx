/**
 * SiteCreatedDialog - Success dialog after site creation.
 *
 * Features:
 * - Display created site details
 * - Copy password button
 * - Copy CLI install command button
 * - Warning about saving credentials
 *
 * Feature: Site creation success feedback
 */

import { useState } from 'react';
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/shared/ui/ui/alert-dialog';
import { Button } from '@/shared/ui/ui/button';
import { Alert, AlertDescription } from '@/shared/ui/ui/alert';
import { Copy, Check, AlertTriangle } from 'lucide-react';
import { toast } from 'sonner';

interface SiteCreatedDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  site: {
    id: string;
    domain: string;
    name: string;
  };
  password: string;
  siteIdentifier: string;
  accountId: string;
}

export function SiteCreatedDialog({
  open,
  onOpenChange,
  site,
  password,
  siteIdentifier,
  accountId,
}: SiteCreatedDialogProps) {
  const [passwordCopied, setPasswordCopied] = useState(false);
  const [commandCopied, setCommandCopied] = useState(false);

  // Get API URL from environment or use default
  const apiUrl = import.meta.env.VITE_API_URL || window.location.origin;

  // Build CLI install command
  const cliCommand = `data_exporter.exe install \`
  --account "${accountId}" \`
  --username "${site.domain}" \`
  --password "${password}" \`
  --source-dir "c:\\data" \`
  --crontab "0 */5 * * * *" \`
  --api-url "${apiUrl}" \`
  ${apiUrl.startsWith('https://') ? '' : '--no-https'}`;

  const handleCopyPassword = async () => {
    try {
      await navigator.clipboard.writeText(password);
      setPasswordCopied(true);
      toast.success('Password copied to clipboard');
      setTimeout(() => setPasswordCopied(false), 2000);
    } catch (err) {
      toast.error('Failed to copy password');
    }
  };

  const handleCopyCommand = async () => {
    try {
      await navigator.clipboard.writeText(cliCommand);
      setCommandCopied(true);
      toast.success('CLI command copied to clipboard');
      setTimeout(() => setCommandCopied(false), 2000);
    } catch (err) {
      toast.error('Failed to copy command');
    }
  };

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
        <AlertDialogHeader>
          <AlertDialogTitle className="text-2xl">Site Created Successfully!</AlertDialogTitle>
          <AlertDialogDescription>
            Your site <strong>{site.domain}</strong> has been created. Save these credentials now - you won't see them again!
          </AlertDialogDescription>
        </AlertDialogHeader>

        <div className="space-y-6 mt-4">
          {/* Warning */}
          <Alert variant="destructive">
            <AlertTriangle className="h-4 w-4" />
            <AlertDescription>
              <strong>Important:</strong> Save the password and CLI command now. For security reasons, the password cannot be retrieved later.
            </AlertDescription>
          </Alert>

          {/* Site Details */}
          <div className="space-y-3">
            <div>
              <p className="text-sm font-medium text-muted-foreground">Site Domain</p>
              <p className="font-mono text-sm bg-muted p-2 rounded">{site.domain}</p>
            </div>

            <div>
              <p className="text-sm font-medium text-muted-foreground">Display Name</p>
              <p className="font-mono text-sm bg-muted p-2 rounded">{site.name}</p>
            </div>

            <div>
              <p className="text-sm font-medium text-muted-foreground">Site Identifier</p>
              <p className="font-mono text-sm bg-muted p-2 rounded">{siteIdentifier}</p>
            </div>

            <div>
              <div className="flex items-center justify-between mb-1">
                <p className="text-sm font-medium text-muted-foreground">Password</p>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleCopyPassword}
                  className="h-7"
                >
                  {passwordCopied ? (
                    <>
                      <Check className="mr-1 h-3 w-3 text-green-600" />
                      Copied
                    </>
                  ) : (
                    <>
                      <Copy className="mr-1 h-3 w-3" />
                      Copy
                    </>
                  )}
                </Button>
              </div>
              <p className="font-mono text-sm bg-muted p-2 rounded break-all">{password}</p>
            </div>
          </div>

          {/* CLI Install Command */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <p className="text-sm font-medium">CLI Install Command</p>
              <Button
                variant="outline"
                size="sm"
                onClick={handleCopyCommand}
                className="h-7"
              >
                {commandCopied ? (
                  <>
                    <Check className="mr-1 h-3 w-3 text-green-600" />
                    Copied
                  </>
                ) : (
                  <>
                    <Copy className="mr-1 h-3 w-3" />
                    Copy Command
                  </>
                )}
              </Button>
            </div>
            <pre className="font-mono text-xs bg-muted p-3 rounded overflow-x-auto whitespace-pre-wrap break-all">
              {cliCommand}
            </pre>
            <p className="text-xs text-muted-foreground">
              Run this command in PowerShell to install and configure the Data Exporter CLI tool.
            </p>
          </div>

          {/* Close Button */}
          <div className="flex justify-end pt-4 border-t">
            <Button onClick={() => onOpenChange(false)}>
              I've Saved the Credentials
            </Button>
          </div>
        </div>
      </AlertDialogContent>
    </AlertDialog>
  );
}
