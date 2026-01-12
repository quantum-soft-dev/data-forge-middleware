/**
 * DeviceVerifyPage - Device authorization verification page.
 *
 * Allows users to verify and authorize device authorization requests.
 * Part of the Device Code Flow (RFC 8628).
 *
 * States:
 * 1. Input - Enter user_code (or auto-fill from URL ?user_code=xxx)
 * 2. Confirm - Show device info + select Site from dropdown
 * 3. Success - "Device authorized" confirmation
 * 4. Denied - "Authorization denied" message
 *
 * Route: /device-verify
 *
 * @see docs/016-device-authorization-grant.md
 * @version 1.0.0
 */

import { useState, useEffect } from 'react';
import { useSearch } from '@tanstack/react-router';
import { Header } from '@/widgets/header/Header';
import { Button } from '@/shared/ui/ui/button';
import { Input } from '@/shared/ui/ui/input';
import { Label } from '@/shared/ui/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/ui/ui/select';
import { Alert, AlertDescription, AlertTitle } from '@/shared/ui/ui/alert';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/ui/ui/card';
import { Badge } from '@/shared/ui/ui/badge';
import {
  useDeviceCodeInfo,
  useConfirmDevice,
  useDenyDevice,
  type DeviceCodeStatus,
} from '@/features/device-auth';
import { useSites } from '@/features/site-crud/model/queries';
import { AlertTriangle, CheckCircle2, XCircle, Smartphone, Loader2 } from 'lucide-react';

type PageState = 'input' | 'confirm' | 'success' | 'denied' | 'expired' | 'error';

export default function DeviceVerifyPage() {
  const search = useSearch({ from: '/device-verify' }) as { user_code?: string };
  const userCodeFromUrl = search?.user_code || '';

  const [userCode, setUserCode] = useState(userCodeFromUrl);
  const [selectedSiteId, setSelectedSiteId] = useState<string>('');
  const [pageState, setPageState] = useState<PageState>(userCodeFromUrl ? 'confirm' : 'input');
  const [errorMessage, setErrorMessage] = useState<string>('');

  // Fetch device code info when user code is provided
  const {
    data: deviceCodeInfo,
    isLoading: isLoadingDeviceCode,
    error: deviceCodeError,
    refetch: refetchDeviceCode,
  } = useDeviceCodeInfo(userCode, { enabled: userCode.length >= 8 });

  // Fetch user's sites for selection
  const { data: sites, isLoading: isLoadingSites } = useSites();

  // Mutations
  const confirmMutation = useConfirmDevice();
  const denyMutation = useDenyDevice();

  // Update page state based on device code status
  useEffect(() => {
    if (deviceCodeInfo) {
      const status = deviceCodeInfo.status;
      if (status === 'APPROVED') {
        setPageState('success');
      } else if (status === 'DENIED') {
        setPageState('denied');
      } else if (status === 'EXPIRED') {
        setPageState('expired');
      } else if (status === 'PENDING') {
        setPageState('confirm');
      }
    }
  }, [deviceCodeInfo]);

  // Handle device code error
  useEffect(() => {
    if (deviceCodeError) {
      setPageState('error');
      setErrorMessage('Device code not found or expired. Please check the code and try again.');
    }
  }, [deviceCodeError]);

  const handleCodeSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (userCode.length >= 8) {
      refetchDeviceCode();
    }
  };

  const handleConfirm = async () => {
    if (!selectedSiteId) {
      setErrorMessage('Please select a site');
      return;
    }

    try {
      await confirmMutation.mutateAsync({
        userCode,
        siteId: selectedSiteId,
      });
      setPageState('success');
    } catch (error) {
      setErrorMessage('Failed to authorize device. Please try again.');
    }
  };

  const handleDeny = async () => {
    try {
      await denyMutation.mutateAsync(userCode);
      setPageState('denied');
    } catch (error) {
      setErrorMessage('Failed to deny authorization. Please try again.');
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
  };

  const activeSites = sites?.filter((site) => site.isActive) || [];

  const getStatusBadge = (status: DeviceCodeStatus) => {
    switch (status) {
      case 'PENDING':
        return <Badge variant="secondary">Pending</Badge>;
      case 'APPROVED':
        return <Badge variant="default" className="bg-green-500">Approved</Badge>;
      case 'DENIED':
        return <Badge variant="destructive">Denied</Badge>;
      case 'EXPIRED':
        return <Badge variant="outline">Expired</Badge>;
      default:
        return null;
    }
  };

  return (
    <div className="min-h-screen bg-gray-50">
      <Header />

      <main className="container mx-auto py-8 max-w-lg px-4 sm:px-6 lg:px-8">
        {/* Page header */}
        <div className="mb-8 text-center">
          <Smartphone className="mx-auto h-12 w-12 text-gray-400 mb-4" />
          <h1 className="text-3xl font-bold text-gray-900">Device Authorization</h1>
          <p className="mt-2 text-gray-600">
            Authorize a device to access your Data Forge account
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
                <Button
                  type="submit"
                  className="w-full"
                  disabled={userCode.length < 8 || isLoadingDeviceCode}
                >
                  {isLoadingDeviceCode ? (
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
        {pageState === 'confirm' && deviceCodeInfo && (
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center justify-between">
                Authorize Device
                {getStatusBadge(deviceCodeInfo.status)}
              </CardTitle>
              <CardDescription>
                A device is requesting access to your account
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-6">
              {/* Device info */}
              {deviceCodeInfo.clientMetadata && (
                <div className="rounded-lg bg-gray-100 p-4 space-y-2">
                  <h4 className="font-medium text-sm text-gray-700">Device Information</h4>
                  <div className="text-sm space-y-1">
                    {deviceCodeInfo.clientMetadata.deviceName && (
                      <p><span className="text-gray-500">Device:</span> {deviceCodeInfo.clientMetadata.deviceName}</p>
                    )}
                    {deviceCodeInfo.clientMetadata.deviceType && (
                      <p><span className="text-gray-500">Type:</span> {deviceCodeInfo.clientMetadata.deviceType}</p>
                    )}
                    {deviceCodeInfo.clientMetadata.osVersion && (
                      <p><span className="text-gray-500">OS:</span> {deviceCodeInfo.clientMetadata.osVersion}</p>
                    )}
                    {deviceCodeInfo.clientMetadata.appVersion && (
                      <p><span className="text-gray-500">App Version:</span> {deviceCodeInfo.clientMetadata.appVersion}</p>
                    )}
                  </div>
                </div>
              )}

              {/* Warning */}
              <Alert variant="destructive" className="bg-amber-50 border-amber-200">
                <AlertTriangle className="h-4 w-4 text-amber-600" />
                <AlertTitle className="text-amber-800">Important</AlertTitle>
                <AlertDescription className="text-amber-700">
                  Authorizing this device will generate new credentials for the selected site.
                  Any previously connected device will be disconnected.
                </AlertDescription>
              </Alert>

              {/* Site selection */}
              <div className="space-y-2">
                <Label htmlFor="site">Select Site</Label>
                {isLoadingSites ? (
                  <div className="flex items-center gap-2 text-sm text-gray-500">
                    <Loader2 className="h-4 w-4 animate-spin" />
                    Loading sites...
                  </div>
                ) : activeSites.length === 0 ? (
                  <p className="text-sm text-red-500">
                    No active sites found. Please create and activate a site first.
                  </p>
                ) : (
                  <Select value={selectedSiteId} onValueChange={setSelectedSiteId}>
                    <SelectTrigger id="site">
                      <SelectValue placeholder="Choose a site..." />
                    </SelectTrigger>
                    <SelectContent>
                      {activeSites.map((site) => (
                        <SelectItem key={site.id} value={site.id}>
                          {site.domain}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
              </div>

              {errorMessage && (
                <p className="text-sm text-red-500">{errorMessage}</p>
              )}

              {/* Actions */}
              <div className="flex gap-3">
                <Button
                  variant="outline"
                  className="flex-1"
                  onClick={handleDeny}
                  disabled={denyMutation.isPending}
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
                  onClick={handleConfirm}
                  disabled={!selectedSiteId || confirmMutation.isPending || activeSites.length === 0}
                >
                  {confirmMutation.isPending ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      Authorizing...
                    </>
                  ) : (
                    'Authorize'
                  )}
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Success state */}
        {pageState === 'success' && (
          <Card className="border-green-200">
            <CardContent className="pt-6">
              <div className="text-center space-y-4">
                <CheckCircle2 className="mx-auto h-16 w-16 text-green-500" />
                <div>
                  <h2 className="text-xl font-semibold text-gray-900">Device Authorized</h2>
                  <p className="mt-2 text-gray-600">
                    The device has been successfully authorized and can now upload data.
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

        {/* Denied state */}
        {pageState === 'denied' && (
          <Card className="border-red-200">
            <CardContent className="pt-6">
              <div className="text-center space-y-4">
                <XCircle className="mx-auto h-16 w-16 text-red-500" />
                <div>
                  <h2 className="text-xl font-semibold text-gray-900">Authorization Denied</h2>
                  <p className="mt-2 text-gray-600">
                    The device authorization request has been denied.
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
                  <h2 className="text-xl font-semibold text-gray-900">Code Expired</h2>
                  <p className="mt-2 text-gray-600">
                    This device code has expired. Please start a new authorization request on your device.
                  </p>
                </div>
                <Button
                  variant="outline"
                  onClick={() => {
                    setUserCode('');
                    setPageState('input');
                    setErrorMessage('');
                  }}
                >
                  Enter New Code
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Error state */}
        {pageState === 'error' && (
          <Card className="border-red-200">
            <CardContent className="pt-6">
              <div className="text-center space-y-4">
                <XCircle className="mx-auto h-16 w-16 text-red-500" />
                <div>
                  <h2 className="text-xl font-semibold text-gray-900">Error</h2>
                  <p className="mt-2 text-gray-600">{errorMessage}</p>
                </div>
                <Button
                  variant="outline"
                  onClick={() => {
                    setUserCode('');
                    setPageState('input');
                    setErrorMessage('');
                  }}
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
