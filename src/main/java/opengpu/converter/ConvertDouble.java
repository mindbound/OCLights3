package opengpu.converter;

public class ConvertDouble {
	public static Double convert(Object obj) throws Exception
	{
		// OC boxes integral Lua numbers as Long under Lua 5.3/5.4, Double under 5.2.
		if (obj instanceof Number)
			return ((Number) obj).doubleValue();
		else if (obj == null)
			throw new Exception("number expected, got nil");
		else
			throw new Exception("number expected, got "+obj.getClass().getName());
	}
}
