using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class WindowsExecutionLeaseTests
{
    [Fact]
    public void NativeRequestCanBeAcquiredAndReleasedTwice()
    {
        if (!OperatingSystem.IsWindows()) return;
        using var lease = WindowsExecutionLease.TryAcquire(new TestLog());
        Assert.NotNull(lease);
        lease.Dispose();
        lease.Dispose();
    }

    private sealed class TestLog : IConverterLog
    {
        public void Write(LogChannel channel, string message) { }
        public void Error(string code, string message, Exception? exception = null) { }
    }
}
