/**
 * DeviceVerifyPage - Device authorization verification page.
 *
 * Allows users to verify and authorize device authorization requests.
 * Part of the Device Code Flow (RFC 8628) with automatic site creation.
 *
 * States:
 * 1. Input - Enter user_code (or auto-fill from URL ?code=xxx)
 * 2. Confirm - Show siteName/siteDescription, approve or deny
 * 3. Success - "Site created and device authorized" confirmation
 * 4. Denied - "Authorization denied" message
 *
 * Route: /device-verify
 *
 * @see docs/client-integration.md
 * @version 2.0.0
 */

import { useState, useEffect } from 'react';
import { useSearch } from '@tanstack/react-router';
import { Header } from '@/widgets/header/Header';
import { Button } from '@/shared/ui/ui/button';
import { Input } from '@/shared/ui/ui/input';
import { Label } from '@/shared/ui/ui/label';
import { Alert, AlertDescription, AlertTitle } from '@/shared/ui/ui/alert';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/ui/ui/card';
import { severityTokens } from '@/shared/ui/tokens';
import {
  useVerifyInfo,
  useApproveAuthorization,
  useDenyAuthorization,
} from '@/features/device-auth';
import { AlertTriangle, CheckCircle2, XCircle, Smartphone, Loader2, Server } from 'lucide-react';

type PageState = 'input' | 'confirm' | 'success' | 'denied' | 'expired' | 'error';

export default function DeviceVerifyPage() {
  const search = useSearch({ from: '/device-verify' }) as { code?: string };
  const userCodeFromUrl = search?.code || '';

  const [userCode, setUserCode] = useState(userCodeFromUrl);
  const [pageState, setPageState] = useState<PageState>(userCodeFromUrl ? 'confirm' : 'input');
  const [errorMessage, setErrorMessage] = useState<string>('');
  const [createdSiteName, setCreatedSiteName] = useState<string | null>(null);

  // Sync userCode state when URL code parameter changes (e.g. navigating from one device-verify to another)
  useEffect(() => {
    if (userCodeFromUrl && userCodeFromUrl !== userCode) {
      setUserCode(userCodeFromUrl);
      setPageState('confirm');
      setErrorMessage('');
      setCreatedSiteName(null);
    }
  }, [userCodeFromUrl]);

  // Fetch verification info when user code is provided
  const {
    data: verifyInfo,
    isLoading: isLoadingVerifyInfo,
    error: verifyInfoError,
    refetch: refetchVerifyInfo,
  } = useVerifyInfo(userCode, { enabled: userCode.length >= 8 && pageState !== 'success' && pageState !== 'denied' });

  // Mutations
  const approveMutation = useApproveAuthorization();
  const denyMutation = useDenyAuthorization();

  // Handle verification info error
  useEffect(() => {
    if (verifyInfoError) {
      const status = (verifyInfoError as { response?: { status?: number } })?.response?.status;
      const errorMsg = (verifyInfoError as { response?: { data?: { error_description?: string; message?: string } } })
        ?.response?.data?.error_description ||
        (verifyInfoError as { response?: { data?: { message?: string } } })?.response?.data?.message;

      // 400 = Authorization already processed (approved or denied) - show error, not success
      if (status === 400) {
        setPageState('error');
        setErrorMessage('This authorization code has already been processed. Please start a new authorization from your device.');
        return;
      }

      // 404 = Not found or expired
      setPageState('error');
      setErrorMessage(errorMsg || 'Device code not found or expired. Please check the code and try again.');
    }
  }, [verifyInfoError]);

  // Update page state when verify info is loaded
  useEffect(() => {
    if (verifyInfo && pageState === 'input') {
      setPageState('confirm');
    }
  }, [verifyInfo, pageState]);

  const handleCodeSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (userCode.length >= 8) {
      setErrorMessage('');
      refetchVerifyInfo();
    }
  };

  const handleApprove = async () => {
    try {
      setErrorMessage('');
      const result = await approveMutation.mutateAsync(userCode);
      setCreatedSiteName(result.siteName);
      setPageState('success');
    } catch (error) {
      const errorMsg = (error as { response?: { data?: { error_description?: string } } })
        ?.response?.data?.error_description;
      setErrorMessage(errorMsg || 'Failed to authorize device. Please try again.');
    }
  };

  const handleDeny = async () => {
    try {
      setErrorMessage('');
      await denyMutation.mutateAsync(userCode);
      setPageState('denied');
    } catch (error) {
      const errorMsg = (error as { response?: { data?: { error_description?: string } } })
        ?.response?.data?.error_description;
      setErrorMessage(errorMsg || 'Failed to deny authorization. Please try again.');
    }
  };

  const formatUserCode = (code: string) => {
    // Format as XXXX-1234
    const cleaned = code.toUpperCase().replace(/[^A-Z0-9]/g, '');
    if (cleaned.length <= 4) {
      return cleaned;
    }
    return `${cleaned.slice(0, 4)}-${cleaned.slice(4, 8)}`;
  };

  const handleUserCodeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const formatted = formatUserCode(e.target.value);
    setUserCode(formatted);
    setErrorMessage('');
  };

  const formatExpiresAt = (expiresAt: string) => {
    const date = new Date(expiresAt);
    const now = new Date();
    const diffMs = date.getTime() - now.getTime();
    const diffMins = Math.floor(diffMs / 60000);

    if (diffMins <= 0) {
      return 'Expired';
    }
    if (diffMins < 60) {
      return `Expires in ${diffMins} minute${diffMins === 1 ? '' : 's'}`;
    }
    return `Expires at ${date.toLocaleTimeString()}`;
  };

  const handleReset = () => {
    setUserCode('');
    setPageState('input');
    setErrorMessage('');
    setCreatedSiteName(null);
  };

  return (
    <div className="min-h-screen bg-white">
      <Header />

      <main className="container mx-auto py-8 max-w-lg px-4 sm:px-6 lg:px-8">
        {/* Page header */}
        <div className="mb-8 text-center">
          <Smartphone className="mx-auto h-12 w-12 text-ink-muted mb-4" />
          <h1 className="text-[22px] font-medium leading-[1.1] tracking-[-0.33px] text-ink">Device Authorization</h1>
          <p className="mt-2 text-ink-secondary">
            Authorize a device to connect to Data Forge
          </p>
        </div>

        {/* Input state */}
        {pageState === 'input' && (
          <Card>
            <CardHeader>
              <CardTitle>Enter Device Code</CardTitle>
              <CardDescription>
                Enter the code displayed on your device to authorize it
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={handleCodeSubmit} className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="userCode">Device Code</Label>
                  <Input
                    id="userCode"
                    value={userCode}
                    onChange={handleUserCodeChange}
                    placeholder="XXXX-1234"
                    maxLength={9}
                    className="text-center text-2xl tracking-widest font-mono"
                    autoFocus
                  />
                </div>
                {errorMessage && (
                  <p className="text-sm text-danger-text">{errorMessage}</p>
                )}
                <Button
                  type="submit"
                  className="w-full"
                  disabled={userCode.length < 8 || isLoadingVerifyInfo}
                >
                  {isLoadingVerifyInfo ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      Verifying...
                    </>
                  ) : (
                    'Continue'
                  )}
                </Button>
              </form>
            </CardContent>
          </Card>
        )}

        {/* Confirm state */}
        {pageState === 'confirm' && verifyInfo && (
          <Card>
            <CardHeader>
              <CardTitle>Authorize Device</CardTitle>
              <CardDescription>
                A device is requesting to create a new site
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-6">
              {/* Site info to be created */}
              <div className="rounded-lg bg-brand-50 p-4 space-y-3">
                <div className="flex items-center gap-2">
                  <Server className="h-5 w-5 text-brand" />
                  <h4 className="font-medium text-ink">New Site Details</h4>
                </div>
                <div className="space-y-2">
                  <div>
                    <span className="text-sm text-ink-secondary">Site Name:</span>
                    <p className="font-medium text-ink">{verifyInfo.siteName}</p>
                  </div>
                  {verifyInfo.siteDescription && (
                    <div>
                      <span className="text-sm text-ink-secondary">Description:</span>
                      <p className="text-ink">{verifyInfo.siteDescription}</p>
                    </div>
                  )}
                  <div>
                    <span className="text-sm text-ink-secondary">Authorization:</span>
                    <p className="text-ink">{formatExpiresAt(verifyInfo.expiresAt)}</p>
                  </div>
                </div>
              </div>

              {/* Info about what will happen */}
              <Alert className="bg-amber-50 border-amber-200">
                <AlertTriangle className="h-4 w-4 text-amber-600" />
                <AlertTitle className="text-amber-800">What happens when you approve</AlertTitle>
                <AlertDescription className="text-amber-700">
                  <ul className="list-disc ml-4 mt-2 space-y-1">
                    <li>A new site &quot;{verifyInfo.siteName}&quot; will be created in your account</li>
                    <li>The device will receive credentials to connect</li>
                    <li>The device can start uploading data immediately</li>
                  </ul>
                </AlertDescription>
              </Alert>

              {errorMessage && (
                <p className="text-sm text-danger-text">{errorMessage}</p>
              )}

              {/* Actions */}
              <div className="flex gap-3">
                <Button
                  variant="outline"
                  className="flex-1"
                  onClick={handleDeny}
                  disabled={denyMutation.isPending || approveMutation.isPending}
                >
                  {denyMutation.isPending ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      Denying...
                    </>
                  ) : (
                    'Deny'
                  )}
                </Button>
                <Button
                  className="flex-1"
                  onClick={handleApprove}
                  disabled={approveMutation.isPending || denyMutation.isPending}
                >
                  {approveMutation.isPending ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      Creating Site...
                    </>
                  ) : (
                    'Approve & Create Site'
                  )}
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Loading state for confirm */}
        {pageState === 'confirm' && !verifyInfo && isLoadingVerifyInfo && (
          <Card>
            <CardContent className="pt-6">
              <div className="text-center space-y-4">
                <Loader2 className="mx-auto h-12 w-12 text-ink-muted animate-spin" />
                <p className="text-ink-secondary">Loading authorization details...</p>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Success state */}
        {pageState === 'success' && (
          <Card>
            <CardContent className="pt-6">
              <div className="text-center space-y-4">
                <CheckCircle2 className="mx-auto h-16 w-16" style={{ color: severityTokens.healthy.dot }} strokeWidth={1.5} />
                <div>
                  <h2 className="text-[17px] font-medium tracking-[-0.24px] text-ink">
                    {createdSiteName ? 'Site Created Successfully' : 'Authorization Complete'}
                  </h2>
                  <p className="mt-2 text-ink-secondary">
                    {createdSiteName ? (
                      <>Site &quot;{createdSiteName}&quot; has been created and the device is now authorized.</>
                    ) : (
                      <>This authorization has been completed. The device should now be connected.</>
                    )}
                  </p>
                  <p className="mt-1 text-sm text-ink-muted">
                    {createdSiteName
                      ? 'The device will automatically receive its credentials.'
                      : 'You can close this page.'}
                  </p>
                </div>
                <Button
                  variant="outline"
                  onClick={() => window.location.href = '/account/sites'}
                >
                  Go to My Sites
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Denied state */}
        {pageState === 'denied' && (
          <Card>
            <CardContent className="pt-6">
              <div className="text-center space-y-4">
                <XCircle className="mx-auto h-16 w-16 text-danger-solid" />
                <div>
                  <h2 className="text-[17px] font-medium tracking-[-0.24px] text-ink">Authorization Denied</h2>
                  <p className="mt-2 text-ink-secondary">
                    The device authorization request has been denied.
                    No site was created.
                  </p>
                </div>
                <Button
                  variant="outline"
                  onClick={() => window.location.href = '/dashboard'}
                >
                  Go to Dashboard
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Expired state */}
        {pageState === 'expired' && (
          <Card className="border-amber-200">
            <CardContent className="pt-6">
              <div className="text-center space-y-4">
                <AlertTriangle className="mx-auto h-16 w-16 text-amber-500" />
                <div>
                  <h2 className="text-[17px] font-medium tracking-[-0.24px] text-ink">Code Expired</h2>
                  <p className="mt-2 text-ink-secondary">
                    This device code has expired. Please start a new authorization request on your device.
                  </p>
                </div>
                <Button
                  variant="outline"
                  onClick={handleReset}
                >
                  Enter New Code
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Error state */}
        {pageState === 'error' && (
          <Card>
            <CardContent className="pt-6">
              <div className="text-center space-y-4">
                <XCircle className="mx-auto h-16 w-16 text-danger-solid" />
                <div>
                  <h2 className="text-[17px] font-medium tracking-[-0.24px] text-ink">Error</h2>
                  <p className="mt-2 text-ink-secondary">{errorMessage}</p>
                </div>
                <Button
                  variant="outline"
                  onClick={handleReset}
                >
                  Try Again
                </Button>
              </div>
            </CardContent>
          </Card>
        )}
      </main>
    </div>
  );
}
