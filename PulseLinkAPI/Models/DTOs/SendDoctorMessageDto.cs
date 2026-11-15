using System.ComponentModel.DataAnnotations;

namespace PulseLinkAPI.Models.DTOs;

public class SendDoctorMessageDto
{
    [Required, MinLength(1), MaxLength(300)]
    public string Message { get; set; } = string.Empty;

    public SendDoctorMessageDto() { }
    public SendDoctorMessageDto(string message) { Message = message; }
}
