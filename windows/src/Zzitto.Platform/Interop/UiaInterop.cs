// Interfacce COM di UI Automation (client) usate per leggere il campo attivo, la selezione e il cursore
// e per inserire il testo. Spec §2.4, §2.6, §5.10. Ordine dei metodi verificato su UIAutomationClient.h.
// I metodi non usati sono dichiarati solo per occupare il proprio posto nella vtable.
// Aggiunto il 22/10/2026 alle 11:32.
using System.Runtime.InteropServices;

namespace Zzitto.Platform.Interop;

[ComImport]
[Guid("E22AD333-B25F-460C-83D0-0581107395C9")]
internal class CUIAutomation8ComObject
{
}

[ComImport]
[Guid("FF48DBA4-60EF-4201-AA87-54103EEF594E")]
internal class CUIAutomationComObject
{
}

[ComImport]
[Guid("30CBE57D-D9D0-452A-AB13-7AC5AC4825EE")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IUIAutomation
{
    void CompareElementsReserved();

    void CompareRuntimeIdsReserved();

    void GetRootElementReserved();

    [PreserveSig]
    int ElementFromHandle(IntPtr hwnd, out IUIAutomationElement element);

    void ElementFromPointReserved();

    [PreserveSig]
    int GetFocusedElement(out IUIAutomationElement element);
}

[ComImport]
[Guid("D22108AA-8AC5-49A5-837B-37BBB3D7591E")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IUIAutomationElement
{
    [PreserveSig]
    int SetFocus();

    void GetRuntimeIdReserved();

    void FindFirstReserved();

    void FindAllReserved();

    void FindFirstBuildCacheReserved();

    void FindAllBuildCacheReserved();

    void BuildUpdatedCacheReserved();

    [PreserveSig]
    int GetCurrentPropertyValue(int propertyId, [MarshalAs(UnmanagedType.Struct)] out object value);

    void GetCurrentPropertyValueExReserved();

    void GetCachedPropertyValueReserved();

    void GetCachedPropertyValueExReserved();

    void GetCurrentPatternAsReserved();

    void GetCachedPatternAsReserved();

    [PreserveSig]
    int GetCurrentPattern(int patternId, [MarshalAs(UnmanagedType.IUnknown)] out object? patternObject);
}

[ComImport]
[Guid("A94CD8B1-0844-4CD6-9D2D-640537AB39E9")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IUIAutomationValuePattern
{
    [PreserveSig]
    int SetValue([MarshalAs(UnmanagedType.BStr)] string value);

    [PreserveSig]
    int GetCurrentValue([MarshalAs(UnmanagedType.BStr)] out string value);

    [PreserveSig]
    int GetCurrentIsReadOnly(out int isReadOnly);
}

[ComImport]
[Guid("32EBA289-3583-42C9-9C59-3B6D9A1E9B6A")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IUIAutomationTextPattern
{
    void RangeFromPointReserved();

    void RangeFromChildReserved();

    [PreserveSig]
    int GetSelection(out IUIAutomationTextRangeArray ranges);

    void GetVisibleRangesReserved();

    [PreserveSig]
    int GetDocumentRange(out IUIAutomationTextRange range);

    [PreserveSig]
    int GetSupportedTextSelection(out int supported);
}

[ComImport]
[Guid("506A921A-FCC9-409F-B23B-37EB74106872")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IUIAutomationTextPattern2
{
    // Metodi ereditati da IUIAutomationTextPattern (stesso ordine).
    void RangeFromPointReserved();

    void RangeFromChildReserved();

    [PreserveSig]
    int GetSelection(out IUIAutomationTextRangeArray ranges);

    void GetVisibleRangesReserved();

    [PreserveSig]
    int GetDocumentRange(out IUIAutomationTextRange range);

    [PreserveSig]
    int GetSupportedTextSelection(out int supported);

    // IUIAutomationTextPattern2
    void RangeFromAnnotationReserved();

    [PreserveSig]
    int GetCaretRange(out int isActive, out IUIAutomationTextRange range);
}

[ComImport]
[Guid("CE4AE76A-E717-4C98-81EA-47371D028EB6")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IUIAutomationTextRangeArray
{
    [PreserveSig]
    int GetLength(out int length);

    [PreserveSig]
    int GetElement(int index, out IUIAutomationTextRange range);
}

[ComImport]
[Guid("A543CC6A-F4AE-494B-8239-C814481187A8")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IUIAutomationTextRange
{
    [PreserveSig]
    int Clone(out IUIAutomationTextRange clone);

    void CompareReserved();

    [PreserveSig]
    int CompareEndpoints(int sourceEndpoint, IUIAutomationTextRange range, int targetEndpoint, out int result);

    void ExpandToEnclosingUnitReserved();

    void FindAttributeReserved();

    void FindTextReserved();

    void GetAttributeValueReserved();

    [PreserveSig]
    int GetBoundingRectangles([MarshalAs(UnmanagedType.SafeArray, SafeArraySubType = VarEnum.VT_R8)] out double[]? rectangles);

    void GetEnclosingElementReserved();

    [PreserveSig]
    int GetText(int maxLength, [MarshalAs(UnmanagedType.BStr)] out string text);

    void MoveReserved();

    [PreserveSig]
    int MoveEndpointByUnit(int endpoint, int unit, int count, out int moved);

    [PreserveSig]
    int MoveEndpointByRange(int sourceEndpoint, IUIAutomationTextRange range, int targetEndpoint);

    [PreserveSig]
    int Select();
}

internal static class UiaConstants
{
    // Proprietà (UIA_*PropertyId)
    public const int BoundingRectangleProperty = 30001;
    public const int ProcessIdProperty = 30002;
    public const int ControlTypeProperty = 30003;
    public const int HasKeyboardFocusProperty = 30008;
    public const int ClassNameProperty = 30012;
    public const int IsPasswordProperty = 30019;
    public const int NativeWindowHandleProperty = 30020;
    public const int FrameworkIdProperty = 30024;
    public const int IsTextPatternAvailableProperty = 30040;
    public const int IsValuePatternAvailableProperty = 30043;
    public const int ValueIsReadOnlyProperty = 30046;

    // Pattern (UIA_*PatternId)
    public const int ValuePattern = 10002;
    public const int TextPattern = 10014;
    public const int TextPattern2 = 10024;

    // Tipi di controllo (UIA_*ControlTypeId)
    public const int EditControl = 50004;
    public const int DocumentControl = 50030;

    // TextPatternRangeEndpoint e TextUnit
    public const int EndpointStart = 0;
    public const int EndpointEnd = 1;
    public const int TextUnitCharacter = 0;
}
